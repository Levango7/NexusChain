# 方法说明

wallet_jar主要是提供普通转账事务的构造，签名，发送以。

## 一、 基本说明

1）、区块确认完成

通过事务的哈希值查询确认区块数，并且确认是否已经完成，
我们认为往后确定2区块即可表示已经完成。
无论什么事务，都要等待至少2个区块确认才算完成

2）、返回格式
```
##### {"message":"","data":[],"statusCode":int}
* message：描述
* data   ：数据
* statusCode：      
   
{
    2000 正确
    2100 已确认
    2200 未确认
    5000 错误
    6000 格式错误
    7000 校验错误
    8000 异常
}
```

## 二、 wallet_jar文档

##### 下载Release里最新的wallet_jar，运行server-0.0.1-SNAPSHOT.jar :
##### java -jar server-0.0.1-SNAPSHOT.jar --server.port="your port" --nodeNet="node ip";

##### 例如：java -jar server-0.0.1-SNAPSHOT.jar --server.port=8080 --nodeNet=00.0.0.000:0000;
##### 所有的调用均为普通rpc
##### Content-Type: application/json;charset=UTF-8

1.0 发起转账申请
```
* ClientToTransferAccount(POST)
* 参数：
* 1）、fromPubkey(十六进制字符串)
* 2）、toPubkeyHash(十六进制字符串)
* 3）、amount（BigDecimal)
* 4）、prikey（十六进制字符串)
* 返回类型：Json
* 返回值：
* {
* data :txHash(事务哈希，十六进制字符串)
* (int)statusCode:int
* (String)message:traninfo（已签名事务，十六进制字符串)
* }
```
* 注意，这里的成功或者失败，仅仅是指动作本身，真正看事务有没有最终成功，还需要通过事务哈希查询确认区块数

	



