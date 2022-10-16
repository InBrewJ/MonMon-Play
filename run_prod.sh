# Get into the /bin directory first!
## and unzip!

export PATH=/home/circle/.sdkman/candidates/java/current/bin:$PATH
cd /home/circle/app
rm -rf ./monmon-play
unzip monmon-play-java--1.0-SNAPSHOT.zip -d ./monmon-play

# TODO: do NOT do killall java, it will also kill the keycloak process running in docker
# The PID exists at:
# /root/projects/MonMon/app/monmon-play/monmon-play-java--1.0-SNAPSHOT/RUNNING_PID
# Just kill the pid there!

# killall java # kill the previous version: not ideal...


# Also not ideal - secureProd.conf isn't easily re-deployable :shrug:
cat /home/circle/app/secureProd.conf > /home/circle/app/monmon-play/monmon-play-java--1.0-SNAPSHOT/conf/prod.conf
nohup ./monmon-play/monmon-play-java--1.0-SNAPSHOT/bin/monmon-play-java- -Dconfig.resource=prod.conf -Dplay.http.secret.key="41fa^pSzvve:iunSpW5HproHJ^EF5Ml1o[1Wfbc[[gOD?jHC;[t?j9Ms0S8=ve</" > ./monmon.out 2> ./monmon.err &