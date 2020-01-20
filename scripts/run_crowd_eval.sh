#!/bin/bash

BASE=`dirname $0`/..
pushd $BASE
{ echo ":load scripts/crowd_eval.scala" & cat <&0; } | sbt -mem 32768 "project evalJVM" console
popd
