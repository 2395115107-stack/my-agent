package com.myagent.team.patrol;

import com.myagent.team.entity.Team;

import java.util.List;

/**
 * 巡检项 SPI:一个实现 = 一种异常的发现逻辑。实现标 @Component 即被编排器纳入巡查。
 * 巡检项只负责"发现问题",不直接写库/发通知 —— 处置与对账统一归 PatrolService,保证幂等与可测试。
 */
public interface PatrolCheck {

    /** 巡检项唯一键(落库 check_key,前端按此分组展示)。 */
    String key();

    /** 人工可读的巡检项名称(前端 tooltip / 通知文案)。 */
    String description();

    /**
     * 对单个团队执行巡检,返回命中的发现(可为空列表)。
     * 实现应只读查询,不做任何写操作;自动处置通过 item.action 声明,由编排器执行。
     */
    List<PatrolItem> check(Team team);

    /**
     * 巡检项产出的一条发现。subject 是去重键的一部分,应稳定(同一异常跨巡次不漂移);
     * entity 是可选的关联实体键(如 "member:analyst-01"),用于同根因告警聚合 —— 同一实体的多条发现合并为一封信箱告警。
     */
    record PatrolItem(String severity, String subject, String detail, String suggestion, String action, String entity) {
        public static PatrolItem warn(String subject, String detail, String suggestion) {
            return new PatrolItem(PatrolFinding.SEVERITY_WARN, subject, detail, suggestion, null, null);
        }
        public static PatrolItem critical(String subject, String detail, String suggestion) {
            return new PatrolItem(PatrolFinding.SEVERITY_CRITICAL, subject, detail, suggestion, null, null);
        }
        public PatrolItem withAction(String action) {
            return new PatrolItem(severity, subject, detail, suggestion, action, entity);
        }
        public PatrolItem withEntity(String entity) {
            return new PatrolItem(severity, subject, detail, suggestion, action, entity);
        }
    }
}
