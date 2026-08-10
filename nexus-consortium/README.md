# Consortium Development Guide

1. git subtree usage

```shell script
git remote add someorigin https://github.com/someproject # add remote
git subtree add --prefix=foldername someorigin master # add folder MonadJ as subtree code directory
git subtree push --prefix=foldername someorigin master # push subtree local change to remote
git subtree pull --prefix=foldername someorigin master # pull from remote 
```

2. spring data jpa usage

https://docs.spring.io/spring-data/jpa/docs/current/reference/html/

3. lombok usage

https://jingyan.baidu.com/article/0a52e3f4e53ca1bf63ed725c.html

4. configurations override

-Dspring.config.location=classpath:\application.yml,some-path\custom-config.yml



## Commands

1. start application: (Windows) 

```.\gradlew consortium:bootRun```

2. clear builds (Windows) 

```.\gradlew consortium:clean```

3. build and run fat jar (Windows)

```shell script
.\gradlew consortium:bootJar       

# override default spring config with your custom config                     
java -jar consortium\build\libs\consortium-0.0.1-SNAPSHOT.jar -Dspring.config.location=classpath:\application.yml,some-path\custom-config.yml

# you can also load your config by environment
set SPRING_CONFIG_LOCATION=classpath:\application.yml,some-path\custom-config.yml 
java -jar consortium\build\libs\consortium-0.0.1-SNAPSHOT.jar
```  

4. rest apis

1. /account/{address} display account 
2. /config display application configuration
