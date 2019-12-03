#!/bin/bash

exec java -jar /opt/jitsi/jirecon/jirecon.jar --conf=/etc/jitsi/jirecon/jirecon.properties >> /var/log/jitsi/jirecon/log.txt
