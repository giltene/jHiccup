FROM azul/zulu-openjdk:8u482-8.92-jre-headless

RUN apt-get update
RUN apt-get -qqy install maven 
