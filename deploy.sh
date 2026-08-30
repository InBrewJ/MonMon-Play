## After sbt dist...

## Move the zipped folder from /target/universal/monmon-play-java--1.0-SNAPSHOT.zip

du -h ./target/universal/monmon-play-java--1.0-SNAPSHOT.zip
scp -o StrictHostKeyChecking=no ./target/universal/monmon-play-java--1.0-SNAPSHOT.zip circle@boom.dryark.uk:/home/circle/app
ssh -o StrictHostKeyChecking=no -v circle@boom.dryark.uk 'bash -s' < run_prod.sh
