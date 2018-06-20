package com.yonyou.iuap.baseservice.bpm.service;

import cn.hutool.core.date.DatePattern;
import cn.hutool.core.util.ReflectUtil;
import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson.JSONObject;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.yonyou.iuap.baseservice.bpm.entity.BpmModel;
import com.yonyou.iuap.baseservice.bpm.utils.BpmExUtil;
import com.yonyou.iuap.baseservice.service.GenericExService;
import com.yonyou.iuap.bpm.pojo.BPMFormJSON;
import com.yonyou.iuap.bpm.service.TenantLimit;
import com.yonyou.iuap.bpm.util.BpmRestVarType;
import com.yonyou.iuap.context.InvocationInfoProxy;
import com.yonyou.iuap.persistence.vo.pub.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import yonyou.bpm.rest.*;
import yonyou.bpm.rest.exception.RestException;
import yonyou.bpm.rest.param.BaseParam;
import yonyou.bpm.rest.request.RestVariable;
import yonyou.bpm.rest.request.historic.BpmHistoricProcessInstanceParam;
import yonyou.bpm.rest.request.historic.HistoricProcessInstancesQueryParam;
import yonyou.bpm.rest.request.historic.HistoricTaskQueryParam;
import yonyou.bpm.rest.request.runtime.ProcessInstanceStartParam;
import yonyou.bpm.rest.request.task.TaskAttachmentResourceParam;
import yonyou.bpm.rest.response.AttachmentResponse;
import yonyou.bpm.rest.response.CommentResponse;
import yonyou.bpm.rest.response.historic.HistoricProcessInstanceResponse;
import yonyou.bpm.rest.response.historic.HistoricTaskInstanceResponse;
import yonyou.bpm.rest.utils.BaseUtils;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.Field;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * 说明：工作流基础Service
 * @author houlf
 * 2018年6月12日
 * 之前版本主要依赖了eiap-plus-common中的BPMSubmitBasicService,可用的方法比较少
 * 后发现在ubpm-modules模块内的example_iuap_bpm、bpm_quickstart等模块中设计了
 * @Refer ProcessService，提供了全面的方法调用，本版本主要参考之
 * @modified by Leon  2018-6-19

 */
public abstract class GenericBpmService<T extends BpmModel> extends GenericExService<T>{

	private Logger log = LoggerFactory.getLogger(GenericBpmService.class);

	@Value("${bpmrest.server}")
	private String serverUrl;
	@Value("${bpmrest.tenant}")
	private String tenant;
	@Value("${bpmrest.token}")
	private String token;

	protected BpmRest bpmRestServices(String userId) {
		if(userId==null){
			throw new IllegalArgumentException("获取BpmRest时传入的userId["+userId+"]是空");
		}
		BaseParam baseParam=new BaseParam();
		baseParam.setOperatorID(userId);
		//1.U审rest服务地址：http://ys.yyuap.com/ubpm-web-rest
		baseParam.setServer(serverUrl);

		//2.==========rest安全调用=========begin
		//租户code
		//管理端租户管理节点生成的token
		baseParam.setTenant(tenant);
		baseParam.setClientToken(token);
		String limitTenantId=TenantLimit.getTenantLimit();
		//==========rest安全调用=========end
		//3.租户隔离，可为空，默认取rest安全多对应的戹
		if(limitTenantId!=null&&!"".equals(limitTenantId.trim())){
			baseParam.setTenantLimitId(limitTenantId);
		}
		return BpmRests.getBpmRest(baseParam);

	}

	/**
	 * 根据流程定义、租户ID和业务key启动流程实例
	 *
	 * @param userId
	 * @param tenantId
	 * @param processKey
	 * @param businessKey
	 * @return
	 * @throws RestException
	 */
	protected Object startProcessByKey(String userId, String processKey, String procInstName, List<RestVariable> variables) throws RestException {
		if (log.isDebugEnabled()) log.debug("启动流程。流程变量数据=" + JSONObject.toJSONString(variables));
		RuntimeService rt = bpmRestServices(userId).getRuntimeService();
		ProcessInstanceStartParam parm = new ProcessInstanceStartParam();
		parm.setProcessDefinitionKey(processKey);
		parm.setVariables(variables);
		parm.setProcessInstanceName(procInstName);
		return rt.startProcess(parm);
	}

	/**
	 * 提交表单
	 *
	 * @param userId
	 * @param processId
	 * @param variables
	 * @return
	 * @throws Exception
	 */
	protected Object startProcessById(String userId, String processId, String procInstName, List<RestVariable> variables)
			throws RestException {
		if (log.isDebugEnabled()) log.debug("启动流程。流程变量数据=" + JSONObject.toJSONString(variables));
		RuntimeService rt = bpmRestServices(userId).getRuntimeService();
		ProcessInstanceStartParam parm = new ProcessInstanceStartParam();
		parm.setProcessDefinitionId(processId);
		parm.setVariables(variables);
		parm.setProcessInstanceName(procInstName);
		return rt.startProcess(parm);
	}

	/**
	 * 获得流程后续task
	 *
	 * @param instanceId
	 * @return
	 * @throws RestException
	 */
	protected ArrayNode queryInstanceNotFinishTaskAssigneeList(String userId, String instanceId)
			throws RestException {

		HistoryService ht = bpmRestServices(userId).getHistoryService();// 历史服务
		JsonNode obj = (JsonNode) ht.getHistoricProcessInstance(instanceId);
		String endTime = obj.get("endTime").textValue();
		if (endTime != null) {
			// 说明该流程实例已结束
			return null;
		}
		HistoricTaskQueryParam htp = new HistoricTaskQueryParam();
		htp.setProcessInstanceId(instanceId);
		htp.setFinished(false);// 只查询下一个未完成的task
		JsonNode jsonNode = (JsonNode) ht.getHistoricTaskInstances(htp);
		ArrayNode arrNode = BaseUtils.getData(jsonNode);
		return arrNode;
	}


	/**
	 * 查询流程所有task列表
	 *
	 * @param userId
	 * @param instanceId
	 * @return
	 * @throws Exception
	 */
	protected ArrayNode queryInstanceAllHistoryTaskList(String userId, String instanceId)
			throws RestException {
		HistoryService ht = bpmRestServices(userId).getHistoryService();// 历史服务
		HistoricTaskQueryParam htp = new HistoricTaskQueryParam();
		htp.setProcessInstanceId(instanceId);
		htp.setIncludeProcessVariables(true);//包含变量
		JsonNode jsonNode = (JsonNode) ht.getHistoricTaskInstances(htp);
		if (log.isDebugEnabled()) log.debug("queryInstanceAllHistoryTaskList==>" + jsonNode);
		if (jsonNode == null) return null;
		ArrayNode arrayNode = BaseUtils.getData(jsonNode);
		return arrayNode;
	}


	/**
	 * 提交某个任务的审批
	 *
	 * @param taskId
	 * @param agreed       是否同意申请
	 * @param auditComment 填写的审批意见
	 * @return 是否提交成功
	 * @throws RestException
	 */
	protected boolean completeTask(String userId, String taskId, boolean agreed, String auditComment)
			throws RestException {
		List<RestVariable> taskVariables = new ArrayList<RestVariable>();
		RestVariable agreeVariable = new RestVariable();
		agreeVariable.setName("agree");
		agreeVariable.setValue(agreed ? "Y" : "N");
		agreeVariable.setVariableScope(RestVariable.RestVariableScope.LOCAL);
		taskVariables.add(agreeVariable);
		TaskService ts = bpmRestServices(userId).getTaskService();
		JsonNode node = (JsonNode) ts.completeWithComment(taskId, taskVariables, null, null,
				auditComment); //TODO 这里需要从结果里拿到流程是否结束的信息,以便更新model中的流程状态
		if (node != null) {
			return true;
		} else {
			return false;
		}
	}
	/**
	 * 部署流程定义
	 * @param userId
	 * @param inputStream
	 * @param name 文件名称
	 * @throws RestException
	 */
	protected void deployment(String userId, InputStream inputStream, String name) throws RestException{
		bpmRestServices(userId).getRepositoryService().postNewDeploymentBPMNFile(inputStream, name);
	}

	/**
	 * 取消抢占
	 *
	 * @param taskId
	 * @return
	 */
	protected boolean withdrawTask(String userId, String taskId) throws RestException {
		return bpmRestServices(userId).getTaskService().withdrawTask(taskId);
	}


	/**
	 * 驳回任务
	 *
	 * @return
	 */
	protected Object rejectToTask(String userId, String processInstanceId,String taskCode ,String comment) throws RestException {
		return   bpmRestServices(userId).getRuntimeService().rejectToActivity(processInstanceId,taskCode,comment);
	}

	/**
	 * 流程终止
	 * @return
	 */
	protected Object suspendProcess(String userId, String processInstanceId) throws RestException {
		return   bpmRestServices(userId).getRuntimeService().suspendProcessInstance(processInstanceId);
	}

	/**
	 * 对一个任务进行评论
	 *
	 * @param userId
	 * @param taskId
	 * @param comment
	 * @param saveInstanceId
	 * @return
	 * @throws Exception
	 */
	protected List<CommentResponse> commentTask(String userId, String taskId, String comment, boolean saveInstanceId)
			throws RestException {
		TaskService ts = bpmRestServices(userId).getTaskService();
		JsonNode obj = (JsonNode) ts.addComment(taskId, comment, saveInstanceId);
		if (log.isDebugEnabled()) log.debug("commentTask 返回:" + obj);
		ArrayNode arr = BaseUtils.getData(obj);
		List<CommentResponse> list = new ArrayList<CommentResponse>(arr.size());
		for (int i = 0; i < arr.size(); i++) {
			JsonNode node = arr.get(i);
			CommentResponse resp = JSONObject.parseObject(node.toString(), CommentResponse.class);
			list.add(resp);
		}
		return list;
	}
	/**
	 * 添加附件
	 * @param userId
	 * @param taskId
	 * @param name
	 * @param desc
	 * @param is
	 * @return
	 * @throws RestException
	 */
	protected AttachmentResponse createAttachment(String userId, String taskId, String name, String desc, InputStream is)
			throws RestException {
		TaskService ts = bpmRestServices(userId).getTaskService();
		TaskAttachmentResourceParam parm = new TaskAttachmentResourceParam();
		parm.setName(name);
		parm.setDescription(desc);
		parm.setValue(is);
		JsonNode obj = (JsonNode) ts.createAttachmentWithContent(taskId, parm);
		//AttachmentResponse ??
		if (log.isDebugEnabled()) {
			log.debug("上传附件返回:" + obj);
		}

		return JSONObject.parseObject (obj.toString(), AttachmentResponse.class);
	}
	/**
	 * 查询附件
	 * @param userId
	 * @param taskId
	 * @return
	 * @throws RestException
	 */
	protected List<AttachmentResponse> queryAttachmentList(String userId, String taskId)
			throws RestException {
		TaskService ts = bpmRestServices(userId).getTaskService();
		JsonNode node = (JsonNode) ts.getAttachments(taskId);

		if (log.isDebugEnabled()) {
			log.debug("获取附件列表返回:" + node);
		}

		ArrayNode arr = BaseUtils.getData(node);
		int size = arr == null ? 0 : arr.size();
		List<AttachmentResponse> list = new ArrayList<AttachmentResponse>(size);
		for (int i = 0; i < size; i++) {
			AttachmentResponse resp = JSONObject.parseObject (arr.get(i).toString(), AttachmentResponse.class);
			list.add(resp);
		}
		return list;
	}
	/**
	 * 获取附件内容
	 * @param userId
	 * @param taskId
	 * @param attachmentId
	 * @return
	 * @throws RestException
	 */
	protected InputStream getAttachment(String userId, String taskId, String attachmentId)
			throws RestException {
		TaskService ts = bpmRestServices(userId).getTaskService();
		byte[] bytes = (byte[]) ts.getAttachmentContent(taskId, attachmentId);
		return new ByteArrayInputStream(bytes);
	}

	/**
	 * 对实例添加comment
	 *
	 * @param userId
	 * @param instanceId
	 * @param comment
	 * @throws Exception
	 */
	protected CommentResponse commentInstance(String userId, String instanceId, String comment)
			throws RestException {
		HistoryService hs = bpmRestServices(userId).getHistoryService();
		JsonNode obj = (JsonNode) hs.addComment(instanceId, comment);
		log.debug("HistoryService.addComment=" + obj);
		if (obj != null)
			return JSONObject.parseObject (obj.toString(), CommentResponse.class);
		return null;
	}
	/**
	 * 查询流程实例全部信息
	 *
	 * @param userId
	 * @param instId
	 * @throws RestException
	 */
	protected JsonNode getProcessInstanceAllInfo(String userId, String instId, BpmHistoricProcessInstanceParam parm) throws RestException {
		HistoryService ht = bpmRestServices(userId).getHistoryService();// 历史服务
		JsonNode node = (JsonNode) ht.getHistoricProcessInstance(instId, parm);
		System.out.println("getProcessInstanceAllInfo=\r\n" + node);
		return node;
	}

	/**
	 * 获得流程实例信息
	 *
	 * @param userId
	 * @param instId
	 * @return
	 * @throws Exception
	 */
	protected HistoricProcessInstanceResponse getProcessInstance(String userId, String instId, boolean includeProcessVariable)
			throws RestException {
		if (log.isDebugEnabled()) log.debug("根据实例id查询实例信息:" + instId);
		HistoryService ht = bpmRestServices(userId).getHistoryService();// 历史服务

		HistoricProcessInstancesQueryParam param = new HistoricProcessInstancesQueryParam();
		param.setProcessInstanceId(instId);
		if (includeProcessVariable)
			param.setIncludeProcessVariables(true);
		else
			param.setIncludeProcessVariables(false);

		JsonNode node = (JsonNode) ht.getHistoricProcessInstances(param);
		if (log.isDebugEnabled()) log.debug("getHistoricProcessInstance=" + node);
		ArrayNode arrNode = BaseUtils.getData(node);
		if (arrNode != null && arrNode.size() > 0) {
			HistoricProcessInstanceResponse resp = JSONObject.parseObject (arrNode.get(0).toString(), HistoricProcessInstanceResponse.class);
			return resp;
		}
		return null;
	}

	protected HistoricTaskInstanceResponse getInstanceNotFinishFirstTask(String userId, String instanceId)
			throws RestException {
		HistoryService ht = bpmRestServices(userId).getHistoryService();// 历史服务
		HistoricTaskQueryParam htp = new HistoricTaskQueryParam();
		htp.setProcessInstanceId(instanceId);
		htp.setFinished(false);// 只查询下一个未完成的task
		htp.setSize(1);
		JsonNode jsonNode = (JsonNode) ht.getHistoricTaskInstances(htp);
		ArrayNode obj2 = BaseUtils.getData(jsonNode);
		int size = obj2.size();
		if (size > 0) {
			HistoricTaskInstanceResponse resp = JSONObject.parseObject (obj2.get(0).toString(), HistoricTaskInstanceResponse.class);
			return resp;
		}
		return null;
	}



/** =========================================================================================================================== */

	/**
	 * 启动工作流
	 */
	public Object startProcess(T entity,String processName) throws RestException{
		List<RestVariable> var = buildOtherVariables(entity);
		try {
			Object result = this.startProcessByKey(InvocationInfoProxy.getUserid(),entity.getProcessDefineCode(),processName,var);
			if ( result!=null) {
				entity.setBpmState(BpmExUtil.BPM_STATE_RUNNING);				//流程状态调整为“运行中”;
				this.save(entity);
				return result;
			}
		} catch (RestException e) {
			throw new BusinessException("驳回流程实例发生错误，请联系管理员！错误原因：" + e.getMessage());
		}
		return null;
	}


	/**
	 *
	 * 提交工作流节点
	 */
	public boolean submit(T entity)  {
		try {
			boolean isSuccess = this.completeTask(InvocationInfoProxy.getUserid(), entity.getTaskId(), true, "");
			if ( isSuccess) {
				entity.setBpmState(BpmExUtil.BPM_STATE_RUNNING);				//流程状态调整为“运行中”;
				this.save(entity);
				return isSuccess;
			}
		} catch (RestException e) {
			throw new BusinessException("提交流程实例发生错误，请联系管理员！错误原因：" + e.getMessage());
		}
		return  false;
	}

	/**
	 * 撤回工作流,或称弃审
	 */
	public boolean revoke(T entity) {
		try {
			boolean isSuccess = this.withdrawTask(InvocationInfoProxy.getUserid(),entity.getTaskId());
			if ( isSuccess) {
				entity.setBpmState(BpmExUtil.BPM_STATE_NOTSTART);				//流程状态调整为“未开始”;
				this.save(entity);
				return isSuccess;
			}
		} catch (RestException e) {
			throw new BusinessException("撤回流程实例发生错误，请联系管理员！错误原因：" + e.getMessage());
		}
		return  false;
	}
	
	/**
	 * 审批流程——更新流程状态
	 */
	public boolean doApprove(String entityId ,boolean agreed,String comment) {
		try {
			if (entityId==null){
				throw new RestException("流程实例未通过BizKey绑定业务实体!");
			}
			T entity = this.findById(entityId);
			boolean isSuccess = this.completeTask(InvocationInfoProxy.getUserid(), entity.getTaskId(), agreed, comment);
			if ( isSuccess) {
				entity.setBpmState(BpmExUtil.BPM_STATE_RUNNING);				//流程状态调整为“运行中”;
				this.save(entity);
				return isSuccess;
			}
		} catch (RestException e) {
			throw new BusinessException("审批流程实例发生错误，请联系管理员！错误原因：" + e.getMessage());
		}
		return  false;
	}
	
	/**
	 * 驳回：更新流程状态——未开始
	 * @param id
	 */
	public Object doReject(T entity,String comment) {
		try {
			Object result = this.rejectToTask(InvocationInfoProxy.getUserid(),entity.getProcessInstanceId(),entity.getTaskKey(),comment);
			if ( result!=null) {
				entity.setBpmState(BpmExUtil.BPM_STATE_RUNNING);				//流程状态调整为“运行中”;
				this.save(entity);
				return result;
			}
		} catch (RestException e) {
			throw new BusinessException("驳回流程实例发生错误，请联系管理员！错误原因：" + e.getMessage());
		}
		return null;
	}
	
	/**
	 * 终止：更新流程状态——人工终止
	 * @param id
	 */
	public Object doSuspendProcess(String id) {
		T entity=   this.findById(id);

		try {
			Object result = this.suspendProcess(InvocationInfoProxy.getUserid(),entity.getProcessInstanceId());
			if ( result!=null) {
				entity.setBpmState(BpmExUtil.BPM_STATE_ABEND);				//流程状态调整为“运行中”;
				this.save(entity);
				return result;
			}
		} catch (RestException e) {
			throw new BusinessException("终止流程实例发生错误，请联系管理员！错误原因：" + e.getMessage());
		}
		return null;
	}
	
	/**
	 * 构建BPMFormJSON
	 * @param processDefineCode
	 * @return
	 * @throws
	 */
	protected BPMFormJSON buildBPMFormJSON(String processDefineCode, T entity){
		try{
			BPMFormJSON bpmForm = new BPMFormJSON();
			bpmForm.setProcessDefinitionKey(processDefineCode);						// 流程定义编码
			bpmForm.setProcessInstanceName(this.getProcessInstance(entity));		// 流程实例名称
			bpmForm.setFormId(entity.getId());										// 单据id
			bpmForm.setBillNo(this.getBpmBillCode(entity));							// 单据号
			bpmForm.setBillMarker(InvocationInfoProxy.getUserid());					// 制单人
			bpmForm.setTitle(this.getTitle(entity));								// 流程标题
			String orgId = "";														// usercxt.getSysUser().getOrgId() ;
			bpmForm.setOrgId(orgId);												// 组织
			bpmForm.setFormUrl(this.getBpmFormUrl(entity));							// 单据url
			String callBackUrl = this.getBpmCallBackUrl(entity);					// 流程审批后，执行的业务处理类(controller对应URI前缀)
			bpmForm.setServiceClass(callBackUrl);
			bpmForm.setOtherVariables(buildOtherVariables(entity));					// 其他变量
			return bpmForm;
		}catch(Exception exp){
			throw new BusinessException("构建BPM参数出错!", exp);
		}
	}

	/**
	 * 构建其他变量，用于提交至流程系统
	 * @param entity
	 * @return
	 */
	protected List<RestVariable> buildOtherVariables(T entity) {
		Field[] fields = ReflectUtil.getFields(entity.getClass());
		List<RestVariable> variables = new ArrayList<RestVariable>();
		for (Field curField : fields) {
			Object fieldValue = ReflectUtil.getFieldValue(entity, curField);
			String variableType = BpmRestVarType.ClassToRestVariavleTypeMap.get(curField.getType());
			if (variableType==null || fieldValue==null) {
				continue;
			}

			RestVariable var = new RestVariable();
			var.setName(curField.getName());
			var.setType(variableType);
			
			if (variableType.equals("date") && fieldValue instanceof Date){
				var.setValue(DatePattern.NORM_DATE_FORMAT.format((Date)fieldValue));
			}else{
				var.setValue(fieldValue);
			}
			variables.add(var);
		}
		return variables;
	}
	
	/**
	 * 获取单据编号
	 * @param entity
	 * @return
	 */
	public String getBpmBillCode(T entity) {
		if(StrUtil.isBlank(entity.getBpmBillCode())) {
			return entity.getId();
		}else {
			return entity.getBpmBillCode();
		}
	}
	
	/**
	 * 获取流程说明Title
	 * @param entity
	 * @return
	 * @throws UnsupportedEncodingException
	 */
	public String getTitle(T entity) throws UnsupportedEncodingException {
		String userName = InvocationInfoProxy.getUsername();
		userName = URLDecoder.decode(userName,"utf-8");
		return "流程[" + entity.getClass().getSimpleName() + "], 单据号：" + entity.getBpmBillCode()
					+"，提交人:"+userName;
	}
	
	/**
	 * 获取流程实例
	 * @param entity
	 * @return
	 * @throws UnsupportedEncodingException
	 */
	public String getProcessInstance(T entity) throws UnsupportedEncodingException {
		return this.getTitle(entity);
	}
	
	/**
	 * 获取流程NodeKey
	 * @param entity
	 * @return
	 */
	public abstract String getNodeKey(T entity);
	
	/**
	 * 获取单据URL
	 * @param entity
	 * @return
	 */
	public abstract String getBpmFormUrl(T entity);
	
	/**
	 * 获取流程回调URL：应用Controller RequestMapping
	 * @param entity
	 * @return
	 */
	public abstract String getBpmCallBackUrl(T entity);

}
