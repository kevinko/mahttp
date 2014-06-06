#!/bin/sh

JAVA="java -server -Xmx512m"
JFLAGS='-classpath build/classes/main:lib/*'

$JAVA $JFLAGS com.faveset.khttpserver.SSLCertGen $*
