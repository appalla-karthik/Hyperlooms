FROM pytorch/pytorch:2.9.1-cuda12.8-cudnn9-runtime@sha256:7b324d212a4450795b49edba9949b7cdc72429148a64e974334bfe5774d51385

RUN mkdir -p /inputs /outputs && chmod 755 /inputs /outputs
COPY --chown=65532:65532 run.py /opt/hyperlooms/run.py
USER 65532:65532
WORKDIR /opt/hyperlooms
ENTRYPOINT ["python", "/opt/hyperlooms/run.py"]
