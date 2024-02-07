FROM ubuntu:20.04

########################################################################
# install java8 + some useful tools
########################################################################
RUN \
    export DEBIAN_FRONTEND=noninteractive \
    && apt-get update -y -q \
    && apt-get install -y \
    aptitude \
    automake \
    bash-completion \
    bison \
    build-essential \
    curl \
    cmake \
    dnsutils \
    elinks \
    emacs-nox emacs-goodies-el \
    fish \
    flex \
    git \
    htop \
    iperf3 \
    iproute2 \
    iputils-ping \
    less \
    libtool \
    libopencv-dev \
    mc \
    nload \
    nmon \
    openjdk-8-jdk \
    psutils \
    source-highlight \
    ssh \
    sudo \
    tmux \
    vim \
    wget \
    && rm -rf /var/lib/apt/lists/*

########################################################################
# install apache flink
########################################################################
ARG FLINK_VERS=1.4.2
RUN \
    cd /tmp && wget -nv "https://archive.apache.org/dist/flink/flink-$FLINK_VERS/flink-$FLINK_VERS-bin-hadoop28-scala_2.11.tgz" \
    && cd /opt/ && tar xfz "/tmp/flink-$FLINK_VERS-bin-hadoop28-scala_2.11.tgz" \
    && ln -s "flink-$FLINK_VERS" flink

EXPOSE 8081

########################################################################
# install scala-related binaries
########################################################################
RUN \
    curl -fL https://github.com/coursier/coursier/releases/latest/download/cs-x86_64-pc-linux.gz \
    | gzip -d > cs && chmod +x cs && ./cs setup -y

########################################################################
# install apache kafka
########################################################################
ARG FLINK_VERS=2.1.1
RUN \
    cd /tmp && wget -nv "https://archive.apache.org/dist/kafka/$FLINK_VERS/kafka_2.11-$FLINK_VERS.tgz" \
    && cd /opt/ && tar xfz "/tmp/kafka_2.11-$FLINK_VERS.tgz" \
    && ln -s "kafka_2.11-$FLINK_VERS" kafka

########################################################################
# update PATH
########################################################################
RUN mkdir -p ~/.config/fish/ \
    && bash -c 'echo "set -gx PATH $PATH /opt/flink/bin /opt/kafka/bin ~/.local/share/coursier/bin" >> ~/.config/fish/config.fish'

COPY . /opt/Jeenk
WORKDIR /opt/Jeenk
