## After sbt dist...

## Move the zipped folder from /target/universal/monmon-play-java--1.0-SNAPSHOT.zip

du -h ./target/universal/monmon-play-java--1.0-SNAPSHOT.zip
scp ./target/universal/monmon-play-java--1.0-SNAPSHOT.zip circle@monmon.urawizard.com:/home/circle/app
