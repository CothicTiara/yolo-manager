"""日志配置"""
import logging
import sys

from ..config import Settings


def setup_logging(settings: Settings) -> None:
    level = logging.DEBUG if settings.debug else logging.INFO
    logging.basicConfig(
        level=level,
        format="%(asctime)s [%(levelname)s] %(name)s - %(message)s",
        stream=sys.stdout,
    )