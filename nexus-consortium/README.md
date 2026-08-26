# Consortium Development Guide

> **构建方式变更（v2.39.0，TD-02）**：本目录自带的 Gradle wrapper 5.2.1（2019 年）
> 及 gradlew / gradlew.bat 已删除——Gradle 5.x 无法驱动 Boot 3.x 插件（Boot 3 要求
> Gradle 7.5+）。nexus-consortium 已作为 composite build 收编进根构建（见仓库根
> settings.gradle 的 `includeBuild 'nexus-consortium'`），**独立构建不再支持**，
> 一律由仓库根目录的 Gradle 8.5 wrapper 驱动：
>
> ```shell script
> # 仓库根目录执行
> gradlew.bat :consortium:build        # 构建 consortium 全部子项
> gradlew.bat :consortium:bootRun      # 启动 consortium 应用
> ```

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

> 以下命令需在**仓库根目录**用根 Gradle wrapper 执行（本目录自带 wrapper 已随 TD-02 删除）。

1. start application: (Windows) 

```gradlew.bat :consortium:bootRun```

2. clear builds (Windows) 

```gradlew.bat :consortium:clean```

3. build and run fat jar (Windows)

```shell script
gradlew.bat :consortium:bootJar       

# override default spring config with your custom config                     
java -jar consortium\build\libs\consortium-0.0.1-SNAPSHOT.jar -Dspring.config.location=classpath:\application.yml,some-path\custom-config.yml

# you can also load your config by environment
set SPRING_CONFIG_LOCATION=classpath:\application.yml,some-path\custom-config.yml 
java -jar consortium\build\libs\consortium-0.0.1-SNAPSHOT.jar
```  

4. rest apis

1. /account/{address} display account 
2. /config display application configuration
