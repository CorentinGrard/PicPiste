# The airfield builder: `extract` and `build` (see airfields.py).
#   docker build -t picpiste-airfields-builder tools/airfields
#   docker run --rm -v "$PWD:/work" picpiste-airfields-builder extract europe.osm.pbf europe-aeroways.osm.pbf
FROM python:3.12-slim-bookworm
# The pyosmium wheel links these system libraries; the slim image lacks libexpat.
RUN apt-get update \
    && apt-get install -y --no-install-recommends libexpat1 zlib1g libstdc++6 libgcc-s1 \
    && rm -rf /var/lib/apt/lists/*
RUN pip install --no-cache-dir osmium==4.3.2 shapely==2.2.0 \
    && python -c "import osmium, shapely"
COPY airfields.py /app/airfields.py
WORKDIR /work
ENTRYPOINT ["python", "/app/airfields.py"]
