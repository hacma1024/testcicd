#!/bin/sh
set -e
echo "Login into Harbor ..."
docker --config ~/.docker/.chuongnp \
login -u $1 -p $2 10.60.156.72
echo "Push image to Harbor ..."
docker --config ~/.docker/.chuongnp \
push 10.60.156.72/ioc/ioc-integration-service:$3
docker rmi 10.60.156.72/ioc/ioc-integration-service:$3
echo "Push done !!!"

#set -e
#
#VERSION=$1
#SERVICE=$2
#
#echo "Push image to registry server"
#docker --config ~/.docker/.chuongnp push 10.60.156.72/ioc/$SERVICE:$VERSION
#docker rmi 10.60.156.72/ioc/$SERVICE:$VERSION
#echo "Finish push image to registry server"
