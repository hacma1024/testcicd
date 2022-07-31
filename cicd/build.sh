#!/bin/sh
set -e
echo "Start build maven ..."
mvn clean install -DskipTests
find target/ -name "*.jar" -print0 | xargs -0 cp -t cicd/docker/
cd cicd/docker
ls -la
echo "Start build docker ..."
docker build . -t 10.60.156.72/ioc/ioc-integration-service:$1
echo "Build done !!!"

#set -e
#
#VERSION=$1
#SERVICE=$2
#
#echo "Start build maven"
#mvn -U clean install -Dmaven.test.skip=true
#echo "Finish build maven"
#
#echo "Start build docker"
#docker build -t 10.60.156.72/ioc/$SERVICE:$VERSION .
#echo "Finish build docker"
