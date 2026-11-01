package com.zz.agent.workflow;

/** 仅供隔离联调注入模拟分配端；本仓库没有真实分配接口适配器。 */
@FunctionalInterface
public interface SimulatedAssignmentPort {

    enum Result { ACCEPTED, REJECTED }

    Result assign(String cookieHeader, long workOrderId, long advisorId);
}
