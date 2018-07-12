package com.yonyou.iuap.baseservice.controller;

import com.yonyou.iuap.base.web.BaseController;
import com.yonyou.iuap.baseservice.entity.Model;
import com.yonyou.iuap.baseservice.entity.annotation.Associative;
import com.yonyou.iuap.baseservice.service.GenericService;
import com.yonyou.iuap.baseservice.vo.GenericAssoVo;
import com.yonyou.iuap.mvc.constants.RequestStatusEnum;
import com.yonyou.iuap.mvc.type.JsonResponse;
import com.yonyou.iuap.mvc.type.SearchParams;
import org.apache.commons.collections.MapUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.io.Serializable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 说明：基础Controller——仅提供主子表关联特性,单表增删改查请参照GenericExController,GenericController
 * TODO 级联删除下个版本支持
 * @author leon
 * 2018年7月11日
 */
@SuppressWarnings("all")
public abstract  class GenericAssoController<T extends Model> extends BaseController {
    private Logger log = LoggerFactory.getLogger(GenericAssoController.class);


    @RequestMapping(value = "/getAssoVo")
    @ResponseBody
    public Object  getAssoVo(PageRequest pageRequest,
                             SearchParams searchParams){
        Serializable id = MapUtils.getString(searchParams.getSearchMap(), "id");
        if (null==id){ return buildSuccess();}
        T entity = service.findById(id);
        Associative associative= entity.getClass().getAnnotation(Associative.class);
        if (associative==null|| StringUtils.isEmpty(associative.fkName())){
            return buildError("","Nothing with @Associative or without fkName",RequestStatusEnum.FAIL_FIELD);
        }
        GenericAssoVo vo = new GenericAssoVo(entity) ;
        for (Class assoKey:subServices.keySet() ){
            List subList= subServices.get(assoKey).queryList(associative.fkName(),id);
            String sublistKey = StringUtils.uncapitalize(assoKey.getSimpleName())+"List";
            vo.addList( sublistKey,subList);
        }
        String entityKey = StringUtils.uncapitalize(entity.getClass().getSimpleName());
        JsonResponse result = this.buildSuccess(entityKey,vo.getEntity());
        result.getDetailMsg().putAll(vo.getSublist());
        return  result;
    }

    @RequestMapping(value = "/SaveAssoVo")
    @ResponseBody
    public Object  saveAssoVo(@RequestBody GenericAssoVo vo){
        T newEntity = service.save((T) vo.getEntity());
        for (Class assoKey:subServices.keySet() ){
            String sublistKey = StringUtils.uncapitalize(assoKey.getSimpleName())+"List";
            if (  vo.getList(sublistKey)!=null && vo.getList(sublistKey).size()>0 )
                subServices.get(assoKey).saveBatch( vo.getList(sublistKey)  );
        }
        return this.buildSuccess(newEntity) ;
    }

    /************************************************************/
    private Map<Class ,GenericService> subServices = new HashMap<>();
    private GenericService<T> service;

    protected void setService(GenericService<T> genericService) {
        this.service = genericService;
    }
    protected void setSubService(Class entityClass, GenericService subService) {
        subServices.put(entityClass,subService);

    }

}
