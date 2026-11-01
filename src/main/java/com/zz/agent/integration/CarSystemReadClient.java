package com.zz.agent.integration;

import java.util.List;

/** 对业务系统只开放当前阶段必需的四种读取能力。 */
public interface CarSystemReadClient {

    Identity currentUser(String cookieHeader);

    WorkOrder getWorkOrder(String cookieHeader, long workOrderId);

    List<Advisor> listAdvisors(String cookieHeader);

    List<Workload> workloads(String cookieHeader);

    record Identity(long id, int userRole, int userStatus) {
    }

    record WorkOrder(long id, int type, int status, Long advisorId, String updateTime) {
    }

    record Advisor(long id, String name, int userRole, int userStatus) {
    }

    record Workload(long advisorId, int processing, int total) {
    }

    final class ReadException extends RuntimeException {
        public enum Kind {
            AUTH_REQUIRED, BUSINESS_FAILURE, HTTP_FAILURE, BAD_RESPONSE, TRANSPORT_FAILURE
        }

        private final Kind kind;
        private final int businessCode;

        public ReadException(Kind kind, int businessCode, String message) {
            super(message);
            this.kind = kind;
            this.businessCode = businessCode;
        }

        public Kind kind() {
            return kind;
        }

        public int businessCode() {
            return businessCode;
        }
    }
}
