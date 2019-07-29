package com.miaoshaproject.service;

import com.miaoshaproject.service.model.PromoModel;

/**
 * @Date: 2019/4/12
 */
public interface PromoService {

    PromoModel getPromoById(Integer itemId);

    //fa发布活动
    void publishPromo(Integer promoId);

    //生产秒杀用的令牌,同时要验证商品信息和用户信息
    String generateSecondKillToken(Integer promoId,Integer itemId,Integer userId);
}