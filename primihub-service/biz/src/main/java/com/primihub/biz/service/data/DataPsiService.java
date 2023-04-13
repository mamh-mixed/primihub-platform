package com.primihub.biz.service.data;

import com.primihub.biz.config.base.OrganConfiguration;
import com.primihub.biz.constant.CommonConstant;
import com.primihub.biz.convert.DataPsiConvert;
import com.primihub.biz.convert.DataResourceConvert;
import com.primihub.biz.entity.base.BaseResultEntity;
import com.primihub.biz.entity.base.BaseResultEnum;
import com.primihub.biz.entity.base.PageDataEntity;
import com.primihub.biz.entity.data.dataenum.TaskStateEnum;
import com.primihub.biz.entity.data.dataenum.TaskTypeEnum;
import com.primihub.biz.entity.data.po.*;
import com.primihub.biz.entity.data.req.*;
import com.primihub.biz.entity.data.vo.DataOrganPsiTaskVo;
import com.primihub.biz.entity.data.vo.DataPsiTaskVo;
import com.primihub.biz.entity.sys.po.SysLocalOrganInfo;
import com.primihub.biz.repository.primarydb.data.DataPsiPrRepository;
import com.primihub.biz.repository.primarydb.data.DataTaskPrRepository;
import com.primihub.biz.repository.secondarydb.data.DataPsiRepository;
import com.primihub.biz.repository.secondarydb.data.DataResourceRepository;
import com.primihub.biz.repository.secondarydb.data.DataTaskRepository;
import com.primihub.biz.util.FileUtil;
import com.primihub.biz.util.snowflake.SnowflakeId;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import javax.annotation.Resource;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
public class DataPsiService {

    @Autowired
    private DataResourceRepository dataResourceRepository;
    @Autowired
    private DataPsiRepository dataPsiRepository;
    @Autowired
    private DataPsiPrRepository dataPsiPrRepository;
    @Autowired
    private DataTaskPrRepository dataTaskPrRepository;
    @Autowired
    private DataResourceService dataResourceService;
    @Autowired
    private OtherBusinessesService otherBusinessesService;
    @Autowired
    private DataTaskRepository dataTaskRepository;
    @Autowired
    private DataAsyncService dataAsyncService;
    @Resource(name="soaRestTemplate")
    private RestTemplate restTemplate;
    @Autowired
    private OrganConfiguration organConfiguration;
    @Autowired
    private DataProjectService dataProjectService;


    public BaseResultEntity getPsiResourceList(DataResourceReq req, Long organId) {
        return dataResourceService.getDataResourceList(req,null);
    }

    public BaseResultEntity getPsiResourceAllocationList(PageReq req, String organId, String serverAddress,String resourceName) {
        SysLocalOrganInfo sysLocalOrganInfo = organConfiguration.getSysLocalOrganInfo();
        String localOrganId = organConfiguration.getSysLocalOrganId();
        if(StringUtils.isBlank(organId) || sysLocalOrganInfo==null || StringUtils.isBlank(sysLocalOrganInfo.getOrganId()) || sysLocalOrganInfo.getOrganId().equals(organId)){
            Map<String,Object> paramMap = new HashMap<>();
            paramMap.put("organId",organId);
            paramMap.put("offset",req.getOffset());
            paramMap.put("pageSize",req.getPageSize());
            paramMap.put("resourceName",resourceName);
            paramMap.put("resourceState",0);
            List<DataResource> dataResources = dataResourceRepository.queryDataResource(paramMap);
            if (dataResources.size()==0){
                return BaseResultEntity.success(new PageDataEntity(0,req.getPageSize(),req.getPageNo(),new ArrayList()));
            }
            Integer count = dataResourceRepository.queryDataResourceCount(paramMap);
            Set<Long> resourceIds = dataResources.stream().map(DataResource::getResourceId).collect(Collectors.toSet());
            List<DataFileField> dataFileField = dataResourceRepository.queryDataFileField(new HashMap() {{
                put("resourceIds", resourceIds);
//                put("relevance", 1);
            }});
            Map<Long, List<DataFileField>> fileFieldMap = dataFileField.stream().collect(Collectors.groupingBy(DataFileField::getResourceId));
            return BaseResultEntity.success(new PageDataEntity(count.intValue(),req.getPageSize(),req.getPageNo(),dataResources.stream().map(re-> DataResourceConvert.DataResourcePoConvertAllocationVo(re,fileFieldMap.get(re.getResourceId()),localOrganId)).collect(Collectors.toList())));
        }else if (!sysLocalOrganInfo.getOrganId().equals(organId)){
            if (StringUtils.isBlank(serverAddress)) {
                return BaseResultEntity.failure(BaseResultEnum.LACK_OF_PARAM,"serverAddress");
            }
            DataFResourceReq fResourceReq = new DataFResourceReq();
            fResourceReq.setPageNo(req.getPageNo());
            fResourceReq.setPageSize(req.getPageSize());
            fResourceReq.setServerAddress(serverAddress);
            fResourceReq.setOrganId(organId);
            fResourceReq.setResourceName(resourceName);
            BaseResultEntity baseResult = otherBusinessesService.getResourceList(fResourceReq);
            if (baseResult.getCode()!=0) {
                return baseResult;
            }
            Map<String,Object> pageData = (LinkedHashMap<String,Object>)baseResult.getResult();
            List<LinkedHashMap<String,Object>> voList = (List<LinkedHashMap<String,Object>>)pageData.getOrDefault("data",new ArrayList<>());
            return BaseResultEntity.success(new PageDataEntity(Integer.valueOf(pageData.getOrDefault("total","0").toString()),Integer.valueOf(pageData.getOrDefault("pageSize","0").toString()),Integer.valueOf(pageData.getOrDefault("index","0").toString()),voList.stream().map(fr->DataResourceConvert.fusionResourceConvertAllocationVo(fr,localOrganId)).collect(Collectors.toList())));
        }else {
            return BaseResultEntity.success(new PageDataEntity(0,req.getPageSize(),req.getPageNo(),new ArrayList()));
        }
    }

    public BaseResultEntity saveDataPsi(DataPsiReq req, Long userId) {
        if (StringUtils.isBlank(req.getServerAddress())){
            return BaseResultEntity.failure(BaseResultEnum.LACK_OF_PARAM,"serverAddress");
        }
        DataPsi dataPsi = DataPsiConvert.DataPsiReqConvertPo(req);
        dataPsi.setUserId(userId);
        dataPsiPrRepository.saveDataPsi(dataPsi);
        DataPsiTask task = new DataPsiTask();
        task.setPsiId(dataPsi.getId());
//        task.setTaskId(UUID.randomUUID().toString());
        task.setTaskId(Long.toString(SnowflakeId.getInstance().nextId()));
        task.setTaskState(0);
        if (dataPsi.getResultOrganIds().contains(",")){
//            task.setAscription("双方获取");
            task.setAscriptionType(1);
        }else {
//            task.setAscription("一方获取");
            task.setAscriptionType(0);
        }
        if (dataPsi.getOutputContent()==0){
//            task.setAscription(task.getAscription()+"交集");
            task.setAscription("求交集");
        }else {
//            task.setAscription(task.getAscription()+"差集");
            task.setAscription("求差集");
        }
        task.setCreateDate(new Date());
        dataPsiPrRepository.saveDataPsiTask(task);
        DataTask dataTask = new DataTask();
        dataTask.setTaskIdName(task.getTaskId());
        dataTask.setTaskName(dataPsi.getResultName());
        dataTask.setTaskState(TaskStateEnum.IN_OPERATION.getStateType());
        dataTask.setTaskType(TaskTypeEnum.PSI.getTaskType());
        dataTask.setTaskStartTime(System.currentTimeMillis());
        dataTaskPrRepository.saveDataTask(dataTask);
        Map<String, Map> organListMap = dataProjectService.getOrganListMap(new ArrayList() {{
            add(dataPsi.getOwnOrganId());
        }}, dataPsi.getServerAddress());
        if (organListMap.containsKey(dataPsi.getOwnOrganId())){
            try {
                Map map = organListMap.get(dataPsi.getOwnOrganId());
                String gatewayAddress = map.get("gatewayAddress").toString();
                String publicKey = (String) map.get("publicKey");
                otherBusinessesService.syncGatewayApiData(new DataPsiTaskSyncReq(task,dataPsi,dataTask),CommonConstant.DISPATCH_RUN_PSI.replace("<address>", gatewayAddress),null);
            }catch (Exception e){
                log.info("Dispatch gatewayAddress api Exception:{}",e.getMessage());
                e.printStackTrace();
            }
            log.info("出去");
        }else {
            log.info("下发失败");
            return BaseResultEntity.failure(BaseResultEnum.DATA_RUN_TASK_FAIL,"任务下发失败,中心节点未获取到机构信息");
        }
//        dataAsyncService.psiGrpcRun(task,dataPsi);
        Map<String, Object> map = new HashMap<>();
        map.put("dataPsi",dataPsi);
        map.put("dataPsiTask",DataPsiConvert.DataPsiTaskConvertVo(task));
        return BaseResultEntity.success(map);
    }

    public BaseResultEntity getPsiTaskList(PageReq req,String resultName) {
        Map<String,Object> paramMap = new HashMap<>();
        paramMap.put("offset",req.getOffset());
        paramMap.put("pageSize",req.getPageSize());
        paramMap.put("resultName",resultName);
        List<DataPsiTaskVo> dataPsiTaskVos = dataPsiRepository.selectPsiTaskPage(paramMap);
        if (dataPsiTaskVos.size()==0){
            return BaseResultEntity.success(new PageDataEntity(0,req.getPageSize(),req.getPageNo(),new ArrayList()));
        }
        Long count = dataPsiRepository.selectPsiTaskPageCount(paramMap);
        return BaseResultEntity.success(new PageDataEntity(count.intValue(),req.getPageSize(),req.getPageNo(),dataPsiTaskVos));
    }

    public BaseResultEntity getOrganPsiTask(Long userId, String resultName, PageReq req) {
        Map<String,Object> paramMap = new HashMap<>();
        paramMap.put("userId",userId);
        paramMap.put("offset",req.getOffset());
        paramMap.put("pageSize",req.getPageSize());
        paramMap.put("resultName",resultName);
        List<DataOrganPsiTaskVo> dataOrganPsiTaskVos = dataPsiRepository.selectOrganPsiTaskPage(paramMap);
        if (dataOrganPsiTaskVos.size()==0) {
            return BaseResultEntity.success(new PageDataEntity(0,req.getPageSize(),req.getPageNo(),new ArrayList()));
        }
        Long count = dataPsiRepository.selectOrganPsiTaskPageCount(paramMap);
        return BaseResultEntity.success(new PageDataEntity(count.intValue(),req.getPageSize(),req.getPageNo(),dataOrganPsiTaskVos));
    }

    public BaseResultEntity getPsiTaskDetails(Long taskId) {
        DataPsiTask task = dataPsiRepository.selectPsiTaskById(taskId);
        if (task==null) {
            return BaseResultEntity.failure(BaseResultEnum.DATA_QUERY_NULL,"未查询到任务信息");
        }
        DataPsi dataPsi = dataPsiRepository.selectPsiById(task.getPsiId());
        if (dataPsi==null) {
            return BaseResultEntity.failure(BaseResultEnum.DATA_QUERY_NULL, "未查询到PSI信息");
        }
        String[] resourceIds = new String[]{dataPsi.getOwnResourceId(),dataPsi.getOtherResourceId()};
        BaseResultEntity baseResult = otherBusinessesService.getResourceListById(dataPsi.getServerAddress(), resourceIds);
        if (baseResult.getCode()!=0)
            return BaseResultEntity.failure(BaseResultEnum.DATA_QUERY_NULL,"查询中心节点资源失败");
        if (baseResult.getResult()==null)
            return BaseResultEntity.failure(BaseResultEnum.DATA_QUERY_NULL,"查询中心节点资源无数据");
        List<LinkedHashMap<String, Object>> mapList = (List<LinkedHashMap<String, Object>>)baseResult.getResult();
        if (mapList.size()!=2)
            return BaseResultEntity.failure(BaseResultEnum.DATA_QUERY_NULL,"查询中心节点资源数据数量异常");
        Map<String, LinkedHashMap<String, Object>> resourceMap = mapList.stream().collect(Collectors.toMap(map -> map.get("resourceId").toString(), Function.identity()));
        return BaseResultEntity.success(DataPsiConvert.DataPsiConvertVo(task,dataPsi,resourceMap.get(dataPsi.getOwnResourceId()),resourceMap.get(dataPsi.getOtherResourceId())));
    }

    public DataPsiTask selectPsiTaskById(Long taskId){
        return dataPsiRepository.selectPsiTaskById(taskId);
    }
    public DataPsi selectPsiById(Long psiId){
        return dataPsiRepository.selectPsiById(psiId);
    }

    public BaseResultEntity delPsiTask(Long taskId) {
        DataPsiTask task = dataPsiRepository.selectPsiTaskById(taskId);
        if (task==null) {
            return BaseResultEntity.failure(BaseResultEnum.DATA_QUERY_NULL);
        }
        if (task.getTaskState()==2) {
            return BaseResultEntity.failure(BaseResultEnum.DATA_DEL_FAIL,"运行中无法删除");
        }
        dataPsiPrRepository.delPsiTask(task.getId());
        dataPsiPrRepository.delPsi(task.getPsiId());
        return BaseResultEntity.success();
    }

    public BaseResultEntity cancelPsiTask(Long taskId) {
        DataPsiTask task = dataPsiRepository.selectPsiTaskById(taskId);
        task.setTaskState(4);
        dataPsiPrRepository.updateDataPsiTask(task);
        return BaseResultEntity.success();
    }

    public BaseResultEntity retryPsiTask(Long taskId) {
        DataPsiTask task = dataPsiRepository.selectPsiTaskById(taskId);
        if (task.getTaskState()==1||task.getTaskState()==2) {
            return BaseResultEntity.failure(BaseResultEnum.DATA_RUN_TASK_FAIL,"运行中或完成");
        }
        if (task.getTaskState()<3) {
            return BaseResultEntity.failure(BaseResultEnum.DATA_RUN_TASK_FAIL,"无法运行");
        }
        DataPsi dataPsi = dataPsiRepository.selectPsiById(task.getPsiId());
        task.setTaskState(2);
        dataPsiPrRepository.updateDataPsiTask(task);
//        dataAsyncService.psiGrpcRun(task,dataPsi);
        return BaseResultEntity.success();
    }

    public BaseResultEntity updateDataPsiResultName(DataPsiReq req) {
        DataPsi dataPsi = dataPsiRepository.selectPsiById(req.getId());
        if (dataPsi==null) {
            return BaseResultEntity.failure(BaseResultEnum.DATA_QUERY_NULL,"未查询到隐私求交信息");
        }
        dataPsi.setResultName(req.getResultName());
        dataPsiPrRepository.updateDataPsi(dataPsi);
        return BaseResultEntity.success();
    }

    public BaseResultEntity syncPsi(DataPsiTask psiTask, DataPsi dataPsi,DataTask dataTask) {
        DataTask dt = dataTaskRepository.selectDataTaskByTaskIdName(dataTask.getTaskIdName());
        if (dt==null){
            psiTask.setId(null);
            dataPsi.setId(null);
            dataTask.setTaskId(null);
            dataPsiPrRepository.saveDataPsi(dataPsi);
            psiTask.setPsiId(dataPsi.getId());
            dataPsiPrRepository.saveDataPsiTask(psiTask);
            dataTaskPrRepository.saveDataTask(dataTask);
        }else {
            dataTask.setTaskId(dt.getTaskId());
            dataTaskPrRepository.updateDataTask(dataTask);
            dataPsiPrRepository.updateDataPsiTaskByTaskId(psiTask);
        }
        return BaseResultEntity.success();
    }
}
