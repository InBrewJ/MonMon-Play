## After sbt dist...

## Move the zipped folder from /target/universal/monmon-play-java--1.0-SNAPSHOT.zip

scp ./target/universal/monmon-play-java--1.0-SNAPSHOT.zip circle@159.65.215.22:/home/circle/app

## Then unzip
## unzip monmon-play-java--1.0-SNAPSHOT.zip -d monmon-play


## Then run with something like
## WHERE THE SECRET SHOULD BE CHANGED!!!
## monmon-play-java-/bin/monmon-play-java- -Dplay.http.secret.key="41fa^pSzvve:iunSpW5HproHJ^EF5Ml1o[1Wfbc[[gOD?jHC;[t?j9Ms0S8=ve</"
##
## ./monmon-play/monmon-play-java--1.0-SNAPSHOT/bin/monmon-play-java- -Dplay.http.secret.key="41fa^pSzvve:iunSpW5HproHJ^EF5Ml1o[1Wfbc[[gOD?jHC;[t?j9Ms0S8=ve</"