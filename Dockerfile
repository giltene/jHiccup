FROM azul/zulu-openjdk:8u482-8.92

RUN apt-get update
RUN apt-get -qqy install maven 
