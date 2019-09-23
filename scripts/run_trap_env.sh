#!/bin/bash

BASE=`dirname $0`/..
pushd $BASE
{ echo ":load scripts/trap_env.scala" & cat <&0; } | sbt -mem 32768 "project exampleJVM" console
popd
