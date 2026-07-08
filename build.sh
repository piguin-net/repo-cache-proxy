#!/usr/bin/sh
docker run -it --rm \
 --user 1000:1000 \
 -v ./repo-cache-proxy:/workspace \
 --workdir /workspace \
 -e MAVEN_ARGS=-Dmaven.repo.local=.m2/repository \
 docker.io/maven:3-eclipse-temurin-25 \
 mvn package
