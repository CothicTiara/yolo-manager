"""YOLOv13 自定义模块兼容层。

背景：平台允许上传外部自训练权重。若权重由 YOLOv13（iMoonLab/yolov13，基于 ultralytics
的改进仓库）训练，pickle 中会引用官方 ultralytics 不存在的模块类（DSC3k2 / DSConv /
HyperACE / C3AH / FullPAD_Tunnel 等），torch.load 反序列化直接抛
"module 'ultralytics.nn.modules.block' has no attribute 'DSC3k2'"，推理与续训均失败。

方案：按 YOLOv13 官方仓库源码原样内置这些模块定义（仅依赖官方 ultralytics 已有的
Conv / C3 / C2f），在加载权重前注入 ultralytics.nn.modules.block / conv 命名空间，
使 pickle 能按原路径解析到类。不修改 site-packages，升级 ultralytics 不受影响。

来源：https://github.com/iMoonLab/yolov13 （ultralytics/nn/modules/conv.py、block.py）
"""
import math

import torch
import torch.nn as nn
import torch.nn.functional as F


def _base_classes():
    """取官方 ultralytics 的 Conv / C3 / C2f 作为基类（签名与 YOLOv13  fork 一致）"""
    from ultralytics.nn.modules.block import C2f, C3
    from ultralytics.nn.modules.conv import Conv

    return Conv, C3, C2f


Conv, C3, C2f = _base_classes()


class DSConv(nn.Module):
    """The Basic Depthwise Separable Convolution.（YOLOv13 conv.py 原样）"""

    def __init__(self, c_in, c_out, k=3, s=1, p=None, d=1, bias=False):
        super().__init__()
        if p is None:
            p = (d * (k - 1)) // 2
        self.dw = nn.Conv2d(
            c_in, c_in, kernel_size=k, stride=s,
            padding=p, dilation=d, groups=c_in, bias=bias
        )
        self.pw = nn.Conv2d(c_in, c_out, 1, 1, 0, bias=bias)
        self.bn = nn.BatchNorm2d(c_out)
        self.act = nn.SiLU()

    def forward(self, x):
        x = self.dw(x)
        x = self.pw(x)
        return self.act(self.bn(x))


class DSBottleneck(nn.Module):
    """An improved bottleneck block using depthwise separable convolutions (DSConv)."""

    def __init__(self, c1, c2, shortcut=True, e=0.5, k1=3, k2=5, d2=1):
        super().__init__()
        c_ = int(c2 * e)
        self.cv1 = DSConv(c1, c_, k1, s=1, p=None, d=1)
        self.cv2 = DSConv(c_, c2, k2, s=1, p=None, d=d2)
        self.add = shortcut and c1 == c2

    def forward(self, x):
        y = self.cv2(self.cv1(x))
        return x + y if self.add else y


class DSC3k(C3):
    """An improved C3k module using DSBottleneck blocks for lightweight feature extraction."""

    def __init__(self, c1, c2, n=1, shortcut=True, g=1, e=0.5, k1=3, k2=5, d2=1):
        super().__init__(c1, c2, n, shortcut, g, e)
        c_ = int(c2 * e)
        self.m = nn.Sequential(
            *(DSBottleneck(c_, c_, shortcut=shortcut, e=1.0, k1=k1, k2=k2, d2=d2)
              for _ in range(n))
        )


class DSC3k2(C2f):
    """An improved C3k2 module that uses lightweight depthwise separable convolution blocks."""

    def __init__(self, c1, c2, n=1, dsc3k=False, e=0.5, g=1, shortcut=True, k1=3, k2=7, d2=1):
        super().__init__(c1, c2, n, shortcut, g, e)
        if dsc3k:
            self.m = nn.ModuleList(
                DSC3k(self.c, self.c, n=2, shortcut=shortcut, g=g, e=1.0, k1=k1, k2=k2, d2=d2)
                for _ in range(n)
            )
        else:
            self.m = nn.ModuleList(
                DSBottleneck(self.c, self.c, shortcut=shortcut, e=1.0, k1=k1, k2=k2, d2=d2)
                for _ in range(n)
            )


class AdaHyperedgeGen(nn.Module):
    """Generates an adaptive hyperedge participation matrix from a set of vertex features."""

    def __init__(self, node_dim, num_hyperedges, num_heads=4, dropout=0.1, context="both"):
        super().__init__()
        self.num_heads = num_heads
        self.num_hyperedges = num_hyperedges
        self.head_dim = node_dim // num_heads
        self.context = context

        self.prototype_base = nn.Parameter(torch.Tensor(num_hyperedges, node_dim))
        nn.init.xavier_uniform_(self.prototype_base)
        if context in ("mean", "max"):
            self.context_net = nn.Linear(node_dim, num_hyperedges * node_dim)
        elif context == "both":
            self.context_net = nn.Linear(2 * node_dim, num_hyperedges * node_dim)
        else:
            raise ValueError(
                f"Unsupported context '{context}'. Expected one of: 'mean', 'max', 'both'."
            )

        self.pre_head_proj = nn.Linear(node_dim, node_dim)
        self.dropout = nn.Dropout(dropout)
        self.scaling = math.sqrt(self.head_dim)

    def forward(self, X):
        B, N, D = X.shape
        if self.context == "mean":
            context_cat = X.mean(dim=1)
        elif self.context == "max":
            context_cat, _ = X.max(dim=1)
        else:
            avg_context = X.mean(dim=1)
            max_context, _ = X.max(dim=1)
            context_cat = torch.cat([avg_context, max_context], dim=-1)
        prototype_offsets = self.context_net(context_cat).view(B, self.num_hyperedges, D)
        prototypes = self.prototype_base.unsqueeze(0) + prototype_offsets

        X_proj = self.pre_head_proj(X)
        X_heads = X_proj.view(B, N, self.num_heads, self.head_dim).transpose(1, 2)
        proto_heads = prototypes.view(B, self.num_hyperedges, self.num_heads, self.head_dim).permute(0, 2, 1, 3)

        X_heads_flat = X_heads.reshape(B * self.num_heads, N, self.head_dim)
        proto_heads_flat = proto_heads.reshape(B * self.num_heads, self.num_hyperedges, self.head_dim).transpose(1, 2)

        logits = torch.bmm(X_heads_flat, proto_heads_flat) / self.scaling
        logits = logits.view(B, self.num_heads, N, self.num_hyperedges).mean(dim=1)
        logits = self.dropout(logits)
        return F.softmax(logits, dim=1)


class AdaHGConv(nn.Module):
    """Performs the adaptive hypergraph convolution (two-stage message passing + residual)."""

    def __init__(self, embed_dim, num_hyperedges=16, num_heads=4, dropout=0.1, context="both"):
        super().__init__()
        self.edge_generator = AdaHyperedgeGen(embed_dim, num_hyperedges, num_heads, dropout, context)
        self.edge_proj = nn.Sequential(nn.Linear(embed_dim, embed_dim), nn.GELU())
        self.node_proj = nn.Sequential(nn.Linear(embed_dim, embed_dim), nn.GELU())

    def forward(self, X):
        A = self.edge_generator(X)
        He = torch.bmm(A.transpose(1, 2), X)
        He = self.edge_proj(He)
        X_new = torch.bmm(A, He)
        X_new = self.node_proj(X_new)
        return X_new + X


class AdaHGComputation(nn.Module):
    """Wrapper applying adaptive hypergraph convolution to 4D feature maps (B,C,H,W)."""

    def __init__(self, embed_dim, num_hyperedges=16, num_heads=8, dropout=0.1, context="both"):
        super().__init__()
        self.embed_dim = embed_dim
        self.hgnn = AdaHGConv(
            embed_dim=embed_dim,
            num_hyperedges=num_hyperedges,
            num_heads=num_heads,
            dropout=dropout,
            context=context
        )

    def forward(self, x):
        B, C, H, W = x.shape
        tokens = x.flatten(2).transpose(1, 2)
        tokens = self.hgnn(tokens)
        x_out = tokens.transpose(1, 2).view(B, C, H, W)
        return x_out


class C3AH(nn.Module):
    """A CSP-style block integrating Adaptive Hypergraph Computation (C3AH)."""

    def __init__(self, c1, c2, e=1.0, num_hyperedges=8, context="both"):
        super().__init__()
        c_ = int(c2 * e)
        assert c_ % 16 == 0, "Dimension of AdaHGComputation should be a multiple of 16."
        num_heads = c_ // 16
        self.cv1 = Conv(c1, c_, 1, 1)
        self.cv2 = Conv(c1, c_, 1, 1)
        self.m = AdaHGComputation(embed_dim=c_,
                                  num_hyperedges=num_hyperedges,
                                  num_heads=num_heads,
                                  dropout=0.1,
                                  context=context)
        self.cv3 = Conv(2 * c_, c2, 1)

    def forward(self, x):
        return self.cv3(torch.cat((self.m(self.cv1(x)), self.cv2(x)), 1))


class FuseModule(nn.Module):
    """Fuse multi-scale features for the HyperACE block (downsample + upsample + concat + conv)."""

    def __init__(self, c_in, channel_adjust):
        super(FuseModule, self).__init__()
        self.downsample = nn.AvgPool2d(kernel_size=2)
        self.upsample = nn.Upsample(scale_factor=2, mode='nearest')
        if channel_adjust:
            self.conv_out = Conv(4 * c_in, c_in, 1)
        else:
            self.conv_out = Conv(3 * c_in, c_in, 1)

    def forward(self, x):
        x1_ds = self.downsample(x[0])
        x3_up = self.upsample(x[2])
        x_cat = torch.cat([x1_ds, x[1], x3_up], dim=1)
        out = self.conv_out(x_cat)
        return out


class HyperACE(nn.Module):
    """Hypergraph-based Adaptive Correlation Enhancement (HyperACE)，YOLOv13 核心模块。"""

    def __init__(self, c1, c2, n=1, num_hyperedges=8, dsc3k=True, shortcut=False,
                 e1=0.5, e2=1, context="both", channel_adjust=True):
        super().__init__()
        self.c = int(c2 * e1)
        self.cv1 = Conv(c1, 3 * self.c, 1, 1)
        self.cv2 = Conv((4 + n) * self.c, c2, 1)
        self.m = nn.ModuleList(
            DSC3k(self.c, self.c, 2, shortcut, k1=3, k2=7) if dsc3k
            else DSBottleneck(self.c, self.c, shortcut=shortcut)
            for _ in range(n)
        )
        self.fuse = FuseModule(c1, channel_adjust)
        self.branch1 = C3AH(self.c, self.c, e2, num_hyperedges, context)
        self.branch2 = C3AH(self.c, self.c, e2, num_hyperedges, context)

    def forward(self, X):
        x = self.fuse(X)
        y = list(self.cv1(x).chunk(3, 1))
        out1 = self.branch1(y[1])
        out2 = self.branch2(y[1])
        y.extend(m(y[-1]) for m in self.m)
        y[1] = out1
        y.append(out2)
        return self.cv2(torch.cat(y, 1))


class DownsampleConv(nn.Module):
    """Average pooling downsampling with optional 1x1 conv channel adjustment."""

    def __init__(self, in_channels, channel_adjust=True):
        super().__init__()
        self.downsample = nn.AvgPool2d(kernel_size=2)
        if channel_adjust:
            self.channel_adjust = Conv(in_channels, in_channels * 2, 1)
        else:
            self.channel_adjust = nn.Identity()

    def forward(self, x):
        return self.channel_adjust(self.downsample(x))


class FullPAD_Tunnel(nn.Module):
    """FullPAD gated fusion: output = original + gate * enhanced（gate 为可学习标量）。"""

    def __init__(self):
        super().__init__()
        self.gate = nn.Parameter(torch.tensor(0.0))

    def forward(self, x):
        out = x[0] + self.gate * x[1]
        return out


# 注入表：pickle 引用的模块路径 -> 兼容类
_BLOCK_CLASSES = {
    "DSBottleneck": DSBottleneck,
    "DSC3k": DSC3k,
    "DSC3k2": DSC3k2,
    "AdaHyperedgeGen": AdaHyperedgeGen,
    "AdaHGConv": AdaHGConv,
    "AdaHGComputation": AdaHGComputation,
    "C3AH": C3AH,
    "FuseModule": FuseModule,
    "HyperACE": HyperACE,
    "DownsampleConv": DownsampleConv,
    "FullPAD_Tunnel": FullPAD_Tunnel,
}
_CONV_CLASSES = {"DSConv": DSConv}


def _fork_aattn_forward(self, x):
    """YOLOv13 fork 版 AAttn 前向（qk/v 分布局，非 flash 分支，数值等价）。

    官方 8.4.x 的 AAttn 已改为融合 qkv 布局，与 fork 权重的 qk/v/proj/pe
    子模块不兼容；pickle 恢复 __dict__ 后按存在哪些子模块选择前向路径。
    """
    B, C, H, W = x.shape
    N = H * W
    qk = self.qk(x).flatten(2).transpose(1, 2)
    v = self.v(x)
    pp = self.pe(v)
    v = v.flatten(2).transpose(1, 2)
    if self.area > 1:
        qk = qk.reshape(B * self.area, N // self.area, C * 2)
        v = v.reshape(B * self.area, N // self.area, C)
        B, N, _ = qk.shape
    q, k = qk.split([C, C], dim=2)
    q = q.transpose(1, 2).view(B, self.num_heads, self.head_dim, N)
    k = k.transpose(1, 2).view(B, self.num_heads, self.head_dim, N)
    v = v.transpose(1, 2).view(B, self.num_heads, self.head_dim, N)
    attn = (q.transpose(-2, -1) @ k) * (self.head_dim ** -0.5)
    max_attn = attn.max(dim=-1, keepdim=True).values
    exp_attn = torch.exp(attn - max_attn)
    attn = exp_attn / exp_attn.sum(dim=-1, keepdim=True)
    x = (v @ attn.transpose(-2, -1)).permute(0, 3, 1, 2)
    if self.area > 1:
        x = x.reshape(B // self.area, N * self.area, C)
        B, N, _ = x.shape
    x = x.reshape(B, H, W, C).permute(0, 3, 1, 2)
    return self.proj(x + pp)


def _make_compat_aattn(official_cls):
    """构造自适应 AAttn：fork 权重（有 qk）走 fork 前向，官方权重（qkv）走原前向"""

    class CompatAAttn(official_cls):
        def forward(self, x):
            if hasattr(self, "qk"):
                return _fork_aattn_forward(self, x)
            return super().forward(x)

    CompatAAttn.__name__ = "AAttn"
    CompatAAttn.__qualname__ = "AAttn"
    return CompatAAttn


_injected = False


def ensure_yolov13_compat() -> bool:
    """把 YOLOv13 自定义模块注入官方 ultralytics 命名空间（幂等）。

    必须在 YOLO(.pt) / torch.load 加载外部权重之前调用，使 pickle 按
    ultralytics.nn.modules.block / conv 原路径解析到这些类。
    同时注入包级 ultralytics.nn.modules 与 tasks 全局命名空间，
    兼容 parse_model 按名称重建模型的代码路径（如续训）。
    官方版本已自带同名类时不覆盖。返回 ultralytics 是否可用。
    """
    global _injected
    try:
        import ultralytics.nn.modules as modules_pkg
        import ultralytics.nn.modules.block as block_mod
        import ultralytics.nn.modules.conv as conv_mod
        import ultralytics.nn.tasks as tasks_mod
    except ImportError:
        return False
    if not _injected:
        for name, cls in _BLOCK_CLASSES.items():
            if not hasattr(block_mod, name):
                setattr(block_mod, name, cls)
            if not hasattr(modules_pkg, name):
                setattr(modules_pkg, name, cls)
            if not hasattr(tasks_mod, name):
                setattr(tasks_mod, name, cls)
        for name, cls in _CONV_CLASSES.items():
            if not hasattr(conv_mod, name):
                setattr(conv_mod, name, cls)
            if not hasattr(modules_pkg, name):
                setattr(modules_pkg, name, cls)
            if not hasattr(tasks_mod, name):
                setattr(tasks_mod, name, cls)
        # AAttn：官方与 fork 同名但子模块布局不同，用自适应子类替换（兼容两种权重）
        official_aattn = getattr(block_mod, "AAttn", None)
        if official_aattn is not None and getattr(official_aattn, "__name__", "") == "AAttn" \
                and not getattr(official_aattn, "_yolov13_compat", False):
            compat_aattn = _make_compat_aattn(official_aattn)
            compat_aattn._yolov13_compat = True
            block_mod.AAttn = compat_aattn
            modules_pkg.AAttn = compat_aattn
            tasks_mod.AAttn = compat_aattn
        _injected = True
    return True