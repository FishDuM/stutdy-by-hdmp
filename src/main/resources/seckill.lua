-- 参数列表
-- 1.1 优惠券id
local voucherId = ARGV[1]
-- 1.2 用户id
local userId = ARGV[2]

-- 2. 数据key
-- 2.1 库存key
local stockKey = 'seckill:stock:' .. voucherId
-- 2.2 订单key（修复：改为order而非stock，避免和库存key冲突）
local orderKey = 'seckill:order:' .. voucherId

-- 3. 脚本业务
-- 3.1 获取库存并处理nil值（库存key不存在时默认0）
local stock = tonumber(redis.call('get', stockKey)) or 0
-- 3.2 判断库存是否充足
if (stock <= 0) then
    -- 库存不足，返回1
    return 1
end

-- 3.3 判断用户是否已下单（SISMEMBER 集合key 成员）
if (redis.call('sismember', orderKey, userId) == 1) then
    -- 重复下单，返回2
    return 2
end

-- 3.4 扣减库存（incrby key -1 表示减1）
redis.call('incrby', stockKey, -1)
-- 3.5 记录用户下单（sadd 集合key 成员）
redis.call('sadd', orderKey, userId)

-- 下单成功，返回0
return 0