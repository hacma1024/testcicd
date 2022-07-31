#!/bin/sh
set -e

TEST_COLOR=$1
VERSION=$2
SERVICE=$3
BASEDIR=$(dirname "$0")

sed -i -e "s,IMAGE_VERSION,$VERSION,g" $BASEDIR/$SERVICE-deployment.yml
sed -i -e "s,TEST_COLOR,$TEST_COLOR,g" $BASEDIR/$SERVICE-deployment.yml
