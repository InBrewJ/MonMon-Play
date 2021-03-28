# Get into the /bin directory first!
## and unzip!

export PATH=/home/circle/.sdkman/candidates/java/current/bin:$PATH
cd /home/circle/app
rm -rf ./monmon-play
unzip monmon-play-java--1.0-SNAPSHOT.zip -d ./monmon-play
killall java # not ideal...
nohup ./monmon-play/monmon-play-java--1.0-SNAPSHOT/bin/monmon-play-java- -Dconfig.resource=prod.conf -Dplay.http.secret.key="41fa^pSzvve:iunSpW5HproHJ^EF5Ml1o[1Wfbc[[gOD?jHC;[t?j9Ms0S8=ve</" > ./monmon.out 2> ./monmon.err &