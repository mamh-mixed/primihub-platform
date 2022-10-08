package com.primihub.biz.repository.secondarydb.data;


import com.primihub.biz.entity.data.po.DataTask;
import com.primihub.biz.entity.data.req.DataPirTaskReq;
import com.primihub.biz.entity.data.vo.DataPirTaskVo;
import org.apache.ibatis.annotations.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Set;

@Repository
public interface DataTaskRepository {
    DataTask selectDataTaskByTaskId(@Param("taskId") Long taskId);

    DataTask selectDataTaskByTaskIdName(@Param("taskIdName") String taskIdName);

    List<DataTask> selectDataTaskByTaskIds(@Param("taskIds") Set<Long> taskIds);

    List<DataPirTaskVo> selectDataPirTaskPage(DataPirTaskReq req);

    Integer selectDataPirTaskCount(DataPirTaskReq req);
}
