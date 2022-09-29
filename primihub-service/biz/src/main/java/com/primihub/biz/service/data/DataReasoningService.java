package com.primihub.biz.service.data;

import com.primihub.biz.config.base.BaseConfiguration;
import com.primihub.biz.convert.DataReasoningConvert;
import com.primihub.biz.entity.base.BaseResultEntity;
import com.primihub.biz.entity.base.BaseResultEnum;
import com.primihub.biz.entity.base.PageDataEntity;
import com.primihub.biz.entity.data.dataenum.TaskStateEnum;
import com.primihub.biz.entity.data.dataenum.TaskTypeEnum;
import com.primihub.biz.entity.data.po.*;
import com.primihub.biz.entity.data.req.DataReasoningReq;
import com.primihub.biz.entity.data.req.DataReasoningResourceReq;
import com.primihub.biz.entity.data.req.ReasoningListReq;
import com.primihub.biz.repository.primarydb.data.DataReasoningPrRepository;
import com.primihub.biz.repository.primarydb.data.DataTaskPrRepository;
import com.primihub.biz.repository.secondarydb.data.DataModelRepository;
import com.primihub.biz.repository.secondarydb.data.DataProjectRepository;
import com.primihub.biz.repository.secondarydb.data.DataReasoningRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class DataReasoningService {
    @Autowired
    private BaseConfiguration baseConfiguration;
    @Autowired
    private DataReasoningPrRepository dataReasoningPrRepository;
    @Autowired
    private DataReasoningRepository dataReasoningRepository;
    @Autowired
    private DataModelRepository dataModelRepository;
    @Autowired
    private DataProjectRepository dataProjectRepository;
    @Autowired
    private DataTaskPrRepository dataTaskPrRepository;
    @Autowired
    private DataAsyncService dataAsyncService;



    public BaseResultEntity getReasoningList(ReasoningListReq req) {
        if (baseConfiguration.getAdminUserIds().contains(req.getUserId()))
            req.setIsAdmin(1);
        List<DataReasoning> dataReasonings = dataReasoningRepository.selectDataReasoninPage(req);
        if (dataReasonings.size()==0){
            return BaseResultEntity.success(new PageDataEntity(0,req.getPageSize(),req.getPageNo(),new ArrayList()));
        }
        Integer tolal = dataReasoningRepository.selectDataReasoninCount(req);
        return BaseResultEntity.success(new PageDataEntity(tolal,req.getPageSize(),req.getPageNo(),dataReasonings.stream().map(DataReasoningConvert::dataReasoningConvertVo).collect(Collectors.toList())));
    }

    public BaseResultEntity saveReasoning(DataReasoningReq req) {
        DataModelTask modelTask = dataModelRepository.queryModelTaskById(req.getTaskId());
        if (modelTask == null)
            return BaseResultEntity.failure(BaseResultEnum.DATA_QUERY_NULL,"没有查询到模型信息");
        DataReasoning dataReasoning = DataReasoningConvert.dataReasoningReqConvertPo(req);
        Set<String> resourceIds = req.getResourceList().stream().map(DataReasoningResourceReq::getResourceId).collect(Collectors.toSet());
        List<DataProjectResource> dataProjectResources = dataProjectRepository.selectProjectResourceByResourceIds(resourceIds);
        if (dataProjectResources.isEmpty()){
            return BaseResultEntity.failure(BaseResultEnum.DATA_QUERY_NULL,"没有查询到资源信息");
        }
        dataReasoningPrRepository.saveDataReasoning(dataReasoning);
        Map<String, String> resourceMap = dataProjectResources.stream().collect(Collectors.toMap(DataProjectResource::getResourceId, DataProjectResource::getServerAddress,(key1, key2) -> key2));
        List<DataReasoningResource> dataReasoningResourceList = req.getResourceList().stream().map(r -> DataReasoningConvert.dataReasoningResourceReqConvertPo(r, dataReasoning.getId(), resourceMap.get(r.getResourceId()))).collect(Collectors.toList());
        dataReasoningPrRepository.saveDataReasoningResources(dataReasoningResourceList);
        Map<String,Object> map = new HashMap<>();
        map.put("id",dataReasoning.getId());
        map.put("reasoningId",dataReasoning.getReasoningId());
        return BaseResultEntity.success(map);
    }

    public BaseResultEntity getReasoning(Long id) {
        DataReasoning dataReasoning = dataReasoningRepository.selectDataReasoninById(id);
        if (dataReasoning==null)
            return BaseResultEntity.failure(BaseResultEnum.DATA_QUERY_NULL,"没有查询到数据");
        return BaseResultEntity.success(DataReasoningConvert.dataReasoningConvertVo(dataReasoning));
    }

    public BaseResultEntity runReasoning(Long id,Long userId){
        DataReasoning dataReasoning = dataReasoningRepository.selectDataReasoninById(id);
        if (dataReasoning==null)
            return BaseResultEntity.failure(BaseResultEnum.DATA_QUERY_NULL,"没有查询到数据");
        List<DataReasoningResource> dataReasoningResources = dataReasoningRepository.selectDataReasoningResource(id);
        if (dataReasoningResources.isEmpty())
            return BaseResultEntity.failure(BaseResultEnum.DATA_QUERY_NULL,"没有查询到资源数据");
        String resourceId = null;
        for (DataReasoningResource dataReasoningResource : dataReasoningResources) {
            if (dataReasoningResource.getParticipationIdentity() == 1){
                resourceId = dataReasoningResource.getResourceId();
            }
        }
        if (resourceId == null)
            return BaseResultEntity.failure(BaseResultEnum.DATA_QUERY_NULL,"未发现发起方数据");
        DataTask dataTask = new DataTask();
        dataTask.setTaskIdName(UUID.randomUUID().toString());
        dataTask.setTaskName(dataReasoning.getReasoningName());
        dataTask.setTaskStartTime(System.currentTimeMillis());
        dataTask.setTaskType(TaskTypeEnum.REASONING.getTaskType());
        dataTask.setTaskState(TaskStateEnum.IN_OPERATION.getStateType());
        dataTask.setTaskUserId(userId);
        dataTaskPrRepository.saveDataTask(dataTask);
        dataReasoning.setRunTaskId(dataTask.getTaskId());
        dataReasoningPrRepository.updateDataReasoning(dataReasoning);
        dataAsyncService.runReasoning(dataTask,dataReasoning,resourceId);
        Map map = new HashMap();
        map.put("taskId",dataTask.getTaskId());
        return BaseResultEntity.success(map);
    }
}
