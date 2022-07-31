#!/bin/sh
set -e

VERSION=$1
ENVIROMENT=$2
SERVICE="ioc-integration-service"
NAMESPACE="ioc"

echo "Run ioc-integration-service"
sed -i -e "s,IMAGE_VERSION,$VERSION,g" cicd/$ENVIROMENT/$SERVICE-deployment.yml

sudo kubectl -n $NAMESPACE apply -f cicd/$ENVIROMENT/$SERVICE-configmap.yml --kubeconfig=cicd/$ENVIROMENT/k8s-config
sudo kubectl -n $NAMESPACE apply -f cicd/$ENVIROMENT/$SERVICE-deployment.yml --kubeconfig=cicd/$ENVIROMENT/k8s-config

echo  'Waiting for deploy'
sleep 20
