#!/bin/sh
set -e

VERSION=$1
ENVIROMENT=$2
SERVICE=$3
TEST_COLOR=$4
NAMESPACE="ioc"

echo "Run SERVICE"
sed -i -e "s,IMAGE_VERSION,$VERSION,g" cicd/$ENVIROMENT/$SERVICE-deployment.yml
sed -i -e "s,TEST_COLOR,$TEST_COLOR,g" cicd/$ENVIROMENT/$SERVICE-deployment.yml

sudo kubectl -n $NAMESPACE apply -f cicd/$ENVIROMENT/$SERVICE-configmap.yml --kubeconfig=cicd/$ENVIROMENT/k8s-config
sudo kubectl -n $NAMESPACE apply -f cicd/$ENVIROMENT/$SERVICE-deployment.yml --kubeconfig=cicd/$ENVIROMENT/k8s-config
sudo kubectl -n $NAMESPACE apply -f cicd/$ENVIROMENT/$SERVICE-svc.yml --kubeconfig=cicd/$ENVIROMENT/k8s-config
sudo kubectl -n $NAMESPACE apply -f cicd/$ENVIROMENT/$SERVICE-ingress.yml --kubeconfig=cicd/$ENVIROMENT/k8s-config

echo "Waiting for deploy"
sleep 20

