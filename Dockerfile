FROM azul/zulu-openjdk:8

RUN apt-get -qqy install maven
RUN apt-get -qqy install git
RUN git clone https://github.com/sgrinev/jHiccup.git

