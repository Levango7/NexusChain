# NexusChainCore-J

NexusChain core procedure with java version

The NexusChain is a brand new intelligent contract and communication platform, based on Block Chain, faced to smart city, achieving the Internet of Things for living facilities and interconnection for living information.

It aims to provide distributed data connection services for many intelligent items of human being, in order to share the data conveniently between personal terminal and the city intelligence terminal, and to share the Identity (ID) authentication, and can provide a consistent access ability of privilege data.

As a full set of solution to build an intelligent city information interconnection, it becomes a connector of a massive number of intelligent systems and devices.

The NexusChain, as a bottom supporting system, will be released in the form of Main Chain, supporting parallel running structure of multi-chains and multi level communication protocol, including device interconnection protocol, terminal data sharing, ID authentication and instant communication protocol etc, achieving the network autonomy with the synchronization of node data through the mixture consensus mechanism by the combination of PoW (proof of work) and PoS (proof of stake), finally achieves the main chain faced to the living information interconnection.  

# NexusChainCore-J Deploy





## 1.	Requirements

### 1.1.	Config network firewall


The default ports of NexusChain's P2P and RPC is 19585. When docker container ports are mapped, they can be modified as required.  
Please modify the network firewall settings to determine whether to open the port.

### 1.2.	hardware

For fullnode operation facilities, the hardware conditions are suggested as follows:
Disk space recommended more than 500GB, memory 16GB, CPU-8 core.

### 1.3.	Install docker、docker-compose

#### 1.3.1.	Ubuntu
```
apt install -y docker-compose
```

#### 1.3.2.	CentOS
```
yum install -y docker

sudo curl -L "https://github.com/docker/compose/releases/download/1.24.0/docker-compose-$(uname -s)-$(uname -m)" -o /usr/local/bin/docker-compose

sudo chmod +x /usr/local/bin/docker-compose
```


See also：[https://docs.docker.com/compose/install/](https://docs.docker.com/compose/install/)


## 2.	YML file

### 2.1.	Example format：


```
version: '3.1'

services:

  nexus_pgsql:
    image: nexus/nexus_pgsql
    restart: always
    container_name: nexus_pgsql
    privileged: true
    volumes:
      - /opt/nexus_pgsql:/var/lib/postgresql/data
    ports:
      - 127.0.0.1:5432:5432
    environment:
      POSTGRES_USER: nexusadmin
      POSTGRES_PASSWORD: PqR_m03VaTsB1M/hyKY
      NEX_POSTGRES_USER: replica
      NEX_POSTGRES_PASSWORD: replica

  nexus_core:
    image: nexus/nexus_core
    restart: always
container_name: nexus_core
    privileged: true
    volumes:
      - /opt/nexus_logs:/logs
    ports:
      - 19585:19585
    environment:
      DATA_SOURCE_URL: 'jdbc:postgresql://nexus_pgsql:5432/postgres'
      DB_USERNAME: 'replica'
      DB_PASSWORD: 'replica'
      ENABLE_MINING: 'false'
      NEX_MINER_COINBASE: ''
      
```

### 2.2.	volumes mapping： 

It can be mapped to different directories as needed.
Among them, nexus_pgsql volumes maps the PostgreSql database data directory. After the docker container is deleted, the directory will not be deleted automatically, and the node data will still be retained.   
If you want to start NEX Core completely, please backup the directory and delete or empty it.


nexus_core volumes map the NEX Core node program log directory.

### 2.3.	ports mapping：

Port mapping of nexus_pgsql is recommended to be mapped to IP address 127.0.0.1 in order to ensure security, and only local access is allowed.  
If you do not want to access the database through an external client, you can also remove the port mapping.  
The external port numbers of nexus_pgsql and nexus_core can be modified as required.

### 2.4.	environment：

The database username password can be customized, but to ensure that NEX_POSTGRES_USER is consistent with DB_USERNAME, NEX_POSTGRES_PASSWORD is consistent with DB_PASSWORD.

ENABLE_MINING indicates whether to start mining.

NEX_MINER_COINBASE must be set for mining coinbase address, otherwise the node cannot start. 

For fullnode that do not participate in mining, an initialization setting is also made.  

The value of DATA_SOURCE_URL is interconnected by docker containers without modification.   
If you need to modify, make sure that the host name in the URL is the PgSQL container name and that the port is the same as the database port inside the PgSQL container.



## 3.	Start docker image

### 3.1.	start command：
```
docker-compose -f nexus.yml up -d
```
（See section 2, YML file for nexus.yml）

The final output of the command is as follows. The table shows that the docker container started successfully.

```
Creating nexus_core ...   

Creating nexus_pgsql ...  
 
Creating nexus_core  
  
Creating nexus_pgsql ... done  
```

### 3.2.	Node Running Check
Command ```docker ps ``` View container status  

The status field is "Up..." and the container is working properly. 

### 3.3.	Log Check

Command ```docker logs -f <CONTAINER ID> ```   
View Node Program Console Output 
/opt/nexus_logs The directory is the directory of log files of node programs. If YML files are mapped to other directories, please go to the corresponding directory to see.

### 3.4.	Upgrade

```
stop and delete container
docker-compose -f nexus.yml down

get latest image
docker pull nexus/nexus_core
```
modify nexus.yml，if necessary。  

```  
enable image
docker-compose -f nexus.yml up -d
```

## 4.	Reference Document
[https://docs.docker.com/](https://docs.docker.com/)  
[https://docs.docker.com/compose/](https://docs.docker.com/compose/)


## 5. IPC Client Instructions

### 5.1 Environment

In the Linux system, the program deployed by docker can use the IPC client

### 5.2 Execution Statement

```bash
# IPC 配套脚本已归档至 scripts/legacy/nexus-core/ipc_client.py
python2 ../../scripts/legacy/nexus-core/ipc_client.py
```

## 6. Run in command line

### Requirements

1. jdk 1.8
2. gradle >= 5.6
3. python, pip >= 3.7
4. postgresql >= 10

### Install python dotenv

```shell script
pip install -U "python-dotenv[cli]" --user
```

### Provide your genesis file and initial validators file

for example, nexus-genesis-generator.json and validators.json

### Create configuration dot env file

for example create local.env in project root directory:

```.env
DATA_SOURCE_URL=jdbc:postgresql://localhost:5432/postgres # 
DB_USERNAME=postgres 
DB_PASSWORD=postgres  
NEX_MINER_COINBASE= # your coinbase address

# clear data in database if enabled 
# CLEAR_DATA=true  

P2P_MODE=grpc # use grpc 
ENABLE_MINING=true # enable mining

BOOTSTRAPS=nexus://192.168.1.142:9586 # bootstrap nodes, split by comma


# your p2p address, provide your network ip address
P2P_ADDRESS=nexus://192.168.1.142:9585 

# enable peers discovery
ENABLE_DISCOVERY=true

GENESIS_FILE=C:\Users\admin\nexus-genesis-generator-test.json # genesis file

VALIDATORS=C:\Users\admin\validators-test.json # initial miners

SERVER_PORT=19585 # rpc port

ALLOW_MINER_JOINS_ERA=1 # enable miner joins at era 1

MAX_BLOCKS_PER_TRANSFER=256 # maximum blocks in a response

CACHE_DIR=C:\Users\Sal\Desktop\configs\leveldb # directory for transaction pool persistence

ENABLE_CODE_ASSERTION=true # enable code assertion for easy debug
```

### Run start script

```bash
# 一键启动脚本已归档至 scripts/legacy/nexus-core/start.py
python ../../scripts/legacy/nexus-core/start.py --env=local.env
```
