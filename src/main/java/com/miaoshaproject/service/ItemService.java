package com.miaoshaproject.service;

import com.miaoshaproject.error.BussinessException;
import com.miaoshaproject.service.model.ItemModel;

import java.util.List;

/**
 * @Auther: Zihaoo
 * @Date: 2019/4/5
 */
public interface ItemService {
    //创建商品
    ItemModel createItem(ItemModel itemModel) throws BussinessException;
    //商品列表浏览
    List<ItemModel> listItem();

    //商品详细浏览
    ItemModel getItemById(Integer id);

    //item及promo mode缓存模型
    ItemModel getItemByIdInCache(Integer id);

    //库存扣减
    boolean decreaseStock(Integer itemId,Integer amount)throws BussinessException;

    //库存回补
    Boolean increaseStock(Integer itemId,Integer amount)throws BussinessException;

    //异步库存扣减
    boolean asyncDecreaseStock(Integer itemId,Integer amount);

    //商品下单后对应销量增加
    void increaseSales(Integer itemId,Integer amount) throws BussinessException;

    //初始化库存流水
    String initStockLog(Integer itemId,Integer amount);

}
