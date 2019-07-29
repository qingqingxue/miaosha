package com.miaoshaproject.service.impl;
import com.miaoshaproject.dao.ItemDOMapper;
import com.miaoshaproject.dao.ItemStockDOMapper;
import com.miaoshaproject.dao.StockLogDoMapper;
import com.miaoshaproject.dataobject.ItemDO;
import com.miaoshaproject.dataobject.ItemStockDO;
import com.miaoshaproject.dataobject.StockLogDo;
import com.miaoshaproject.error.BussinessException;
import com.miaoshaproject.error.EmBusinessError;
import com.miaoshaproject.mq.MqProducer;
import com.miaoshaproject.service.ItemService;
import com.miaoshaproject.service.PromoService;
import com.miaoshaproject.service.model.ItemModel;
import com.miaoshaproject.service.model.PromoModel;
import com.miaoshaproject.valiator.ValidationResult;
import com.miaoshaproject.valiator.ValidatorImpl;

import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.beans.Beans;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * @Date: 2019/4/5
 */
@Service
public class ItemServiceImpl implements ItemService {
    @Autowired
    private MqProducer mqProducer;

    @Autowired
    private ValidatorImpl validator;
    @Autowired
    private ItemDOMapper itemDOMapper;
    @Autowired
    private ItemStockDOMapper itemStockDOMapper;

    @Autowired
    private RedisTemplate redisTemplate;

    @Autowired
    private StockLogDoMapper stockLogDoMapper;

    //初始化对应的库存流水,在下单之前调用
    @Override
    @Transactional
    public String initStockLog(Integer itemId, Integer amount) {
        StockLogDo stockLogDo = new StockLogDo();
        stockLogDo.setItemId(itemId);
        stockLogDo.setAmount(amount);
        stockLogDo.setStockLogId(UUID.randomUUID().toString().replace("-","")); //使用UUID生成库存流水状态ID
        stockLogDo.setStatus(1);  //1-初始化   2-完成     3-需要回滚

        stockLogDoMapper.insert(stockLogDo);
        return stockLogDo.getStockLogId();
    }

    @Override
    public ItemModel getItemByIdInCache(Integer id) {
        ItemModel itemModel = (ItemModel)redisTemplate.opsForValue().get("item_validate_"+id);
        if(itemModel == null){
            itemModel = this.getItemById(id);
            redisTemplate.opsForValue().set("item_validate_"+id,itemModel);
            //所有缓存都需要设置一个缓存期
            redisTemplate.expire("item_validate"+id,10, TimeUnit.MINUTES);
        }
        return itemModel;
    }

    @Autowired
    private PromoService promoService;

    private ItemDO convertItemDOFromItemModel(ItemModel itemModel){
        if(itemModel == null) {
            return null;
        }
        ItemDO itemDO = new ItemDO();
        BeanUtils.copyProperties(itemModel,itemDO);
        return itemDO;
    }

    private ItemStockDO convertItemStockDOFromItemModel(ItemModel itemModel){
        if(itemModel == null) {
            return null;
        }
        ItemStockDO itemStockDO = new ItemStockDO();
        itemStockDO.setItemId(itemModel.getId());
        itemStockDO.setStock(itemModel.getStock());
        return itemStockDO;
    }

    @Override
    @Transactional
    public ItemModel createItem(ItemModel itemModel) throws BussinessException {
        //校验入参
        ValidationResult result = validator.validate(itemModel);
        if(result.isHasErrors()){
            throw new BussinessException(EmBusinessError.PARAMETER_VALIDATION_ERROR,result.getErrMsg());
        }
        //转化itemMode-》dataobject
        ItemDO itemDO = this.convertItemDOFromItemModel(itemModel);

        //写入数据库
        itemDOMapper.insertSelective(itemDO);
        itemModel.setId(itemDO.getId());

        ItemStockDO itemStockDO = this.convertItemStockDOFromItemModel(itemModel);
        itemStockDOMapper.insertSelective(itemStockDO);

        //返回创建完成的对象，这里要返回存入数据库的对象
        return this.getItemById(itemModel.getId());
    }
    //商品列表页面浏览
    @Override
    public List<ItemModel> listItem() {
        List<ItemDO> itemDOList = itemDOMapper.listItem();

        List<ItemModel> itemModelList = itemDOList.stream().map(itemDO -> {
            ItemStockDO itemStockDO = itemStockDOMapper.selectByItemId(itemDO.getId());
            ItemModel itemModel = this.convertModelFromDataObject(itemDO,itemStockDO);
            return itemModel;
        }).collect(Collectors.toList());
        return itemModelList;
    }


    @Override
    public ItemModel getItemById(Integer id) {
        ItemDO itemDO = itemDOMapper.selectByPrimaryKey(id);
        if(itemDO == null) {
            return null;
        }
        //操作获得库存数量
        ItemStockDO itemStockDO = itemStockDOMapper.selectByItemId(itemDO.getId());

        //将dataobject转换成model
        ItemModel itemModel = this.convertModelFromDataObject(itemDO,itemStockDO);

        //获取商品秒杀活动信息
        PromoModel promoModel = promoService.getPromoById(itemModel.getId());
        if(promoModel != null && promoModel.getStatus().intValue() != 3) {
            itemModel.setPromoModel(promoModel);
        }

        return itemModel;
    }

    @Override
    @Transactional     //减库存需要用事务来保证一致性
    public boolean decreaseStock(Integer itemId, Integer amount)throws BussinessException {

//        int affectedRow = itemStockDOMapper.decreaseStock(itemId,amount);//改为从缓存中减库存
       long result =  redisTemplate.opsForValue().increment("promo_item_stock_"+itemId,amount.intValue()*-1);//完成减一后的数据

        if(result > 0){
            //更新库存成功,mq发送一条消息出去，让异步消息感知到来减数据库的库存
//            boolean mqResult = mqProducer.asyncReduceStock(itemId,amount);
//            if(!mqResult){
//                redisTemplate.opsForValue().increment("promo_item_stock_"+itemId,amount.intValue());
//                return false;
//            }
            return true;
        }else if(result == 0){
            //打上库存售罄标志
            redisTemplate.opsForValue().set("promo_item_stock_invalid_"+itemId,"true");
            return true;
        }else{
            //更新库存失败
//            redisTemplate.opsForValue().increment("promo_item_stock_"+itemId,amount.intValue());
            increaseStock(itemId,amount);
            return false;
        }
    }

    @Override
    public boolean asyncDecreaseStock(Integer itemId, Integer amount) {
        boolean mqResult = mqProducer.asyncReduceStock(itemId,amount);
        return mqResult;
    }

    @Override
    public Boolean increaseStock(Integer itemId, Integer amount) throws BussinessException {
        redisTemplate.opsForValue().increment("promo_item_stock_"+itemId,amount.intValue());
        return true;
    }

    @Override
    @Transactional
    public void increaseSales(Integer itemId, Integer amount) throws BussinessException {
        itemDOMapper.increaseSales(itemId,amount);

    }

    private ItemModel convertModelFromDataObject(ItemDO itemDO,ItemStockDO itemStockDO){
        ItemModel itemModel = new ItemModel();
        BeanUtils.copyProperties(itemDO,itemModel);
        itemModel.setStock(itemStockDO.getStock());

        return itemModel;
    }
}
