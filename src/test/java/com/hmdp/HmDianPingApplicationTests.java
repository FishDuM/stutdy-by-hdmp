package com.hmdp;

import com.hmdp.entity.Voucher;
import com.hmdp.service.IVoucherService;
import com.hmdp.service.impl.ShopServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@SpringBootTest
public class HmDianPingApplicationTests {
    @Resource
    private ShopServiceImpl shopService;

    @Resource
    private IVoucherService voucherService;

    @Test
    void testSaveShop(){
        shopService.saveShop2Redis(1L,10L);
    }

    @Test
    void contextLoads() throws Exception {
        Voucher voucher = new Voucher();
        voucher.setShopId(1L);  // 商铺ID
        voucher.setTitle("100元代金券");  // 标题
        voucher.setSubTitle("周一至周五均可使用");  // 副标题
        voucher.setRules("全场通用\\n无需预约\\n可无限叠加\\n不兑现，不找零\\n仅限堂食");  // 使用规则
        voucher.setPayValue(8000L);  // 支付金额
        voucher.setActualValue(10000L);  // 实际金额
        voucher.setType(1);  // 类型
        voucher.setStatus(1);  // 状态（可根据需要调整）
        voucher.setStock(100);  // 库存
        // 时间字段格式化
        DateTimeFormatter formatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
        voucher.setBeginTime(LocalDateTime.parse("2026-01-18T17:30:00", formatter));  // 开始时间
        voucher.setEndTime(LocalDateTime.parse("2026-02-19T12:00:00", formatter));  // 结束时间
        voucher.setCreateTime(LocalDateTime.now());  // 设置当前时间为创建时间
        voucher.setUpdateTime(LocalDateTime.now());  // 设置当前时间为更新时间
        voucherService.addSeckillVoucher(voucher);
    }
}
