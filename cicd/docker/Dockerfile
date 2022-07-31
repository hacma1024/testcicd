FROM 10.60.156.72/library/openjdk:8-jdk-alpine
MAINTAINER chuongnp

RUN cp /usr/share/zoneinfo/Asia/Ho_Chi_Minh /etc/localtime

ADD ./ioc-integration-service.jar /app/

CMD ["java", "-Xmx200m", "-jar", "/app/ioc-integration-service.jar"]

EXPOSE 9998
