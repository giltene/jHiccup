FROM azul/zulu-openjdk:8

RUN apt-get update
RUN apt-get -qqy install maven 
