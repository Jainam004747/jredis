# Supported commands

jredis implements 90 commands (plus `QUIT`). They behave like Redis 7 for the options listed; other options return `ERR syntax error`.

## Keys

| Command | Changes data | Arity |
|---|---|---|
| `DEL` | yes | at least 2 |
| `UNLINK` | yes | at least 2 |
| `EXISTS` | no | at least 2 |
| `TYPE` | no | exactly 2 |
| `KEYS` | no | exactly 2 |
| `DBSIZE` | no | exactly 1 |
| `FLUSHALL` | yes | at least 1 |
| `FLUSHDB` | yes | at least 1 |
| `EXPIRE` | yes | exactly 3 |
| `PEXPIRE` | yes | exactly 3 |
| `EXPIREAT` | yes | exactly 3 |
| `PEXPIREAT` | yes | exactly 3 |
| `TTL` | no | exactly 2 |
| `PTTL` | no | exactly 2 |
| `EXPIRETIME` | no | exactly 2 |
| `PEXPIRETIME` | no | exactly 2 |
| `PERSIST` | yes | exactly 2 |
| `RENAME` | yes | exactly 3 |

## Strings

| Command | Changes data | Arity |
|---|---|---|
| `SET` | yes | at least 3 |
| `GET` | no | exactly 2 |
| `GETDEL` | yes | exactly 2 |
| `GETSET` | yes | exactly 3 |
| `SETNX` | yes | exactly 3 |
| `SETEX` | yes | exactly 4 |
| `PSETEX` | yes | exactly 4 |
| `MGET` | no | at least 2 |
| `MSET` | yes | at least 3 |
| `INCR` | yes | exactly 2 |
| `DECR` | yes | exactly 2 |
| `INCRBY` | yes | exactly 3 |
| `DECRBY` | yes | exactly 3 |
| `INCRBYFLOAT` | yes | exactly 3 |
| `APPEND` | yes | exactly 3 |
| `STRLEN` | no | exactly 2 |
| `GETRANGE` | no | exactly 4 |

## Lists

| Command | Changes data | Arity |
|---|---|---|
| `LPUSH` | yes | at least 3 |
| `RPUSH` | yes | at least 3 |
| `LPUSHX` | yes | at least 3 |
| `RPUSHX` | yes | at least 3 |
| `LPOP` | yes | at least 2 |
| `RPOP` | yes | at least 2 |
| `LLEN` | no | exactly 2 |
| `LRANGE` | no | exactly 4 |
| `LINDEX` | no | exactly 3 |
| `LSET` | yes | exactly 4 |
| `LTRIM` | yes | exactly 4 |
| `LREM` | yes | exactly 4 |

## Hashes

| Command | Changes data | Arity |
|---|---|---|
| `HSET` | yes | at least 4 |
| `HMSET` | yes | at least 4 |
| `HSETNX` | yes | exactly 4 |
| `HGET` | no | exactly 3 |
| `HMGET` | no | at least 3 |
| `HDEL` | yes | at least 3 |
| `HGETALL` | no | exactly 2 |
| `HKEYS` | no | exactly 2 |
| `HVALS` | no | exactly 2 |
| `HLEN` | no | exactly 2 |
| `HEXISTS` | no | exactly 3 |
| `HINCRBY` | yes | exactly 4 |

## Sets

| Command | Changes data | Arity |
|---|---|---|
| `SADD` | yes | at least 3 |
| `SREM` | yes | at least 3 |
| `SMEMBERS` | no | exactly 2 |
| `SISMEMBER` | no | exactly 3 |
| `SCARD` | no | exactly 2 |
| `SINTER` | no | at least 2 |
| `SUNION` | no | at least 2 |
| `SDIFF` | no | at least 2 |

## Sorted sets

| Command | Changes data | Arity |
|---|---|---|
| `ZADD` | yes | at least 4 |
| `ZINCRBY` | yes | exactly 4 |
| `ZREM` | yes | at least 3 |
| `ZSCORE` | no | exactly 3 |
| `ZCARD` | no | exactly 2 |
| `ZRANK` | no | exactly 3 |
| `ZREVRANK` | no | exactly 3 |
| `ZRANGE` | no | at least 4 |
| `ZREVRANGE` | no | at least 4 |
| `ZRANGEBYSCORE` | no | at least 4 |
| `ZCOUNT` | no | exactly 4 |

## Server and connection

| Command | Changes data | Arity |
|---|---|---|
| `PING` | no | at least 1 |
| `ECHO` | no | exactly 2 |
| `TIME` | no | exactly 1 |
| `SELECT` | no | exactly 2 |
| `COMMAND` | no | at least 1 |
| `CLIENT` | no | at least 2 |
| `INFO` | no | at least 1 |
| `SAVE` | no | exactly 1 |
| `BGSAVE` | no | at least 1 |
| `BGREWRITEAOF` | no | exactly 1 |
| `LASTSAVE` | no | exactly 1 |
| `CONFIG` | no | at least 2 |

Arity counts the command name itself, Redis-style: `GET key` has arity 2.
