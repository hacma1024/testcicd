#!/bin/sh
set -e

ACTIVE_COLOR=$1
TEST_COLOR=$2
SERVICE=$3
BASEDIR=$(dirname "$0")

sed -i -e "s,ACTIVE_COLOR,$ACTIVE_COLOR,g" $BASEDIR/$SERVICE-route.yml
sed -i -e "s,TEST_COLOR,$TEST_COLOR,g" $BASEDIR/$SERVICE-route.yml
