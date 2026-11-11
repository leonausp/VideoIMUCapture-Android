# Pinned Python environment for pb3_export.py: protobuf (with protoc via grpcio-tools), numpy,
# OpenCV (headless, decodes the MP4), PyYAML. Nothing to install on the host but Docker.
FROM python:3.12.7-slim-bookworm

RUN pip install --no-cache-dir --root-user-action=ignore \
        protobuf==5.28.3 \
        grpcio-tools==1.66.2 \
        numpy==2.1.3 \
        opencv-python-headless==4.10.0.84 \
        pyyaml==6.0.2

WORKDIR /work
