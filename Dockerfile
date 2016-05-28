FROM azul/zulu-openjdk:8

RUN apt-get install maven
RUN apt-get install git
RUN git clone https://github.com/sgrinev/jHiccup.git
RUN cd jHiccup
RUN mvn test
