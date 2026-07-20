"""Environment-driven production entrypoint for CPU serving containers."""

from __future__ import annotations


def create_app_from_env():
    from .serving import ServingConfig
    from .serving_app import create_serving_app

    return create_serving_app(ServingConfig.from_env())


def main() -> None:
    try:
        import uvicorn
    except ImportError as exc:
        raise RuntimeError(
            "Vision serving requires optional dependencies; "
            "install with `pip install -e \".[serving]\"`"
        ) from exc

    from .serving import ServingConfig
    config = ServingConfig.from_env()
    uvicorn.run(
        "vision_ai.serving_entrypoint:create_app_from_env",
        factory=True,
        host=config.host,
        port=config.port,
        workers=config.workers,
    )


if __name__ == "__main__":
    main()
