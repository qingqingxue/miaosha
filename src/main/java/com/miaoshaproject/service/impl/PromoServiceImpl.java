package com.miaoshaproject.service.impl;

import com.miaoshaproject.dao.PromoDOMapper;
import com.miaoshaproject.dataobject.PromoDO;
import com.miaoshaproject.error.BussinessException;
import com.miaoshaproject.error.EmBusinessError;
import com.miaoshaproject.service.ItemService;
import com.miaoshaproject.service.PromoService;
import com.miaoshaproject.service.UserService;
import com.miaoshaproject.service.model.ItemModel;
import com.miaoshaproject.service.model.PromoModel;
import com.miaoshaproject.service.model.UserModel;
import org.joda.time.DateTime;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * @Date: 2019/4/12
 */
@Service
public class PromoServiceImpl implements PromoService {

    @Autowired
    PromoDOMapper promoDOMapper;

    @Autowired
    ItemService itemService;

    @Autowired
    RedisTemplate redisTemplate;

    @Autowired
    UserService userService;

    @Override
    public String generateSecondKillToken(Integer promoId,Integer itemId,Integer userId) {
        //判断库存是否已售罄，若对应key存在，则直接下单失败
        if(redisTemplate.hasKey("promo_item_stock_invalid_"+itemId)){
            return null;
        }
        //查询相关的promoDO
        PromoDO promoDO = promoDOMapper.selectByPrimaryKey(promoId);
        PromoModel promoModel = convertFromDataObject(promoDO);
        if(promoModel == null) {
            return null;
        }

        //判断秒杀活动的当前状态
        DateTime now = new DateTime();
        if(promoModel.getStartDate().isAfterNow()){
            promoModel.setStatus(1);
        } else if(promoModel.getEndDate().isBeforeNow()) {
            promoModel.setStatus(3);
        } else {
            promoModel.setStatus(2);
        }
        //判断活动是否正在进行
        if(promoModel.getStatus() !=2){
            //如果活动没开始，直接返回null
            return null;
        }
        //判断商品是否存在
        ItemModel itemModel = itemService.getItemByIdInCache(itemId);
        if(itemModel == null) {
           return null;
        }
        //判断用户信息是否存在
        UserModel userModel = userService.getUserById(userId);
        if(userId == null) {
           return null;
        }

        //获取秒杀大闸的count数量
        long result = redisTemplate.opsForValue().increment("promo_door_count_"+promoId,-1);
        if(result < 0){
            return null;
        }
        //不然生成令牌token，用来做活动验证
        String token = UUID.randomUUID().toString().replace("-","");
        redisTemplate.opsForValue().set("promo_token_"+promoId+"userId_"+userId+"itemId_"+itemId,token);
        redisTemplate.expire("promo_token_"+promoId+"userId_"+userId+"itemId_"+itemId,5, TimeUnit.MINUTES);

        return null;
    }

    /**应有一个入口点交给运营人员去发布商品
     * 模拟：用一个controller模拟发布
     * @param promoId
     */
    @Override
    public void publishPromo(Integer promoId) {
        //通过活动id获取活动
        PromoDO promoDO = promoDOMapper.selectByPrimaryKey(promoId);
        if(promoDO.getItemId() == null || promoDO.getItemId().intValue()==0){
            return;
        }
        ItemModel itemModel = itemService.getItemById(promoDO.getItemId());
        //先暂时默认在入缓存到开售之前，库存不会发生变化，实际情况中要考虑这个问题，会设计商品上架下架问题
        redisTemplate.opsForValue().set("promo_item_stock_"+itemModel.getId(),itemModel.getStock());

        //将大闸的限制数字设置到redis内
        redisTemplate.opsForValue().set("promo_door_count_"+promoId,itemModel.getStock().intValue()*5);
    }

    @Override
    public PromoModel getPromoById(Integer itemId) {
        //获取对应商品的秒杀信息
        PromoDO promoDO = promoDOMapper.selectByItemId(itemId);
        PromoModel promoModel = convertFromDataObject(promoDO);
        if(promoModel == null) {
            return null;
        }

        //判断秒杀活动的当前状态
        DateTime now = new DateTime();
        if(promoModel.getStartDate().isAfterNow()){
            promoModel.setStatus(1);
        } else if(promoModel.getEndDate().isBeforeNow()) {
            promoModel.setStatus(3);
        } else {
            promoModel.setStatus(2);
        }


        return promoModel;
    }

    private PromoModel convertFromDataObject(PromoDO promoDO){
        if(promoDO == null) {
            return null;
        }
        PromoModel promoModel = new PromoModel();
        BeanUtils.copyProperties(promoDO,promoModel);
        promoModel.setStartDate(new DateTime(promoDO.getStartDate()));
        promoModel.setEndDate(new DateTime(promoDO.getEndDate()));

        return promoModel;
    }
}
