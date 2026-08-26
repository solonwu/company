package com.company.formplatform.auth.service;

import com.company.formplatform.auth.dto.LoginResponse;
import com.company.formplatform.auth.enums.EmpRank;
import com.company.formplatform.common.entity.Department;
import com.company.formplatform.common.entity.User;
import com.company.formplatform.common.enums.DeptType;
import com.company.formplatform.common.enums.UserStatus;
import com.company.formplatform.common.exception.ResourceNotFoundException;
import com.company.formplatform.common.repository.DepartmentRepository;
import com.company.formplatform.common.repository.RoleRepository;
import com.company.formplatform.common.repository.UserRepository;
import com.company.formplatform.common.service.AdminAccessService;
import com.company.formplatform.common.service.DeptCodeLookupService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * EMP 是外部（既有）行員資料表，不是本專案的 JPA 實體，做法比照
 * {@link com.company.formplatform.form.lookup.ExternalLookupServiceImpl}：
 * 直接用 JdbcTemplate 唯讀查詢，不透過 Hibernate 管理／驗證這張表的 schema。
 * SQL 裡的識別字一律用雙引號括起來，保留 EMP 表建立時的原始大小寫與中文欄位名稱
 * （SQL Server 與 PostgreSQL 皆支援 ANSI 雙引號識別字語法，可跨兩種資料庫共用同一段 SQL）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LoginServiceImpl implements LoginService {

    private static final String SELECT_COLUMNS =
            "SELECT \"行員代號\", \"姓名\", \"服務單位\", \"部室別\", \"主管別\" FROM \"EMP\" WHERE ";
    private static final String SQL_BY_SESSION = SELECT_COLUMNS + "\"sessionid\" = ?";
    private static final String SQL_BY_EMPLOYEE_CODE = SELECT_COLUMNS + "\"行員代號\" = ?";
    private static final String SQL_BY_SERVICE_UNIT = SELECT_COLUMNS + "\"服務單位\" = ?";

    private static final String HEAD_OFFICE_SERVICE_UNIT = "070";
    private static final String GENERAL_AFFAIRS_DEPT_PREFIX = "B";

    private final JdbcTemplate jdbcTemplate;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final DepartmentRepository departmentRepository;
    private final DeptCodeLookupService deptCodeLookupService;
    private final AdminAccessService adminAccessService;

    /**
     * 2026-08-26 修正：這兩個進入點以前沒有 @Transactional，內部呼叫 resolveAndProvision()
     * → ensureAppUser() 屬於同一個 bean 內的 self-invocation，繞過 Spring AOP proxy，
     * 讓 ensureAppUser() 上的 @Transactional 完全沒有生效；專案又設定 open-in-view: false
     * （application.yml），沒有 OSIV 兜底，導致既有帳號（roles 是尚未初始化的 lazy collection）
     * 重新登入時，user.getRoles().clear() 在沒有 Session 的情況下丟 LazyInitializationException
     * （新帳號因為 roles 是 new HashSet() 不是 proxy，不會炸，所以只有「回頭登入既有帳號」才會踩到）。
     * 在這裡（外部呼叫會經過 proxy 的公開方法）加上 @Transactional 即可讓整條呼叫鏈共用同一個
     * Session，包含內部 self-invocation 的部分。
     */
    @Override
    @Transactional
    public LoginResponse login(String sessionId) {
        return resolveAndProvision(SQL_BY_SESSION, sessionId, "sessionid 無效或已過期");
    }

    @Override
    @Transactional
    public LoginResponse loginByEmployeeCode(String employeeCode) {
        return resolveAndProvision(SQL_BY_EMPLOYEE_CODE, employeeCode, "查無此行員代號");
    }

    private LoginResponse resolveAndProvision(String sql, String param, String notFoundReason) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, param);
        if (rows.isEmpty()) {
            throw new ResourceNotFoundException("查無對應的登入身分（" + notFoundReason + "）");
        }
        LoginResponse identity = toResponse(rows.get(0));
        ensureAppUser(identity);
        return identity;
    }

    private LoginResponse toResponse(Map<String, Object> row) {
        String employeeCode = (String) row.get("行員代號");
        String name = (String) row.get("姓名");
        String serviceUnit = (String) row.get("服務單位");
        String deptCode = (String) row.get("部室別");
        String managerCode = (String) row.get("主管別");
        String normalizedDeptCode = deptCode == null || deptCode.isEmpty() ? null : deptCode;

        return new LoginResponse(
                employeeCode,
                name,
                serviceUnit,
                deptCode,
                managerCode,
                resolveRank(managerCode),
                HEAD_OFFICE_SERVICE_UNIT.equals(serviceUnit),
                deptCode != null && !deptCode.isEmpty(),
                deptCode != null && deptCode.startsWith(GENERAL_AFFAIRS_DEPT_PREFIX),
                deptCodeLookupService.findUnitName(serviceUnit).orElse(null),
                deptCodeLookupService.findTopLevelDeptName(serviceUnit, normalizedDeptCode).orElse(null),
                adminAccessService.isAdmin(employeeCode)
        );
    }

    /**
     * 讓查到的 EMP 身分可以真的拿來送出/簽核表單：app_user 尚無對應帳號
     * （account = 行員代號）時自動建立一筆，並依推導出的職級掛上對應的通用 Role
     * （role_code 與 {@link EmpRank} 列舉值同名，例如 "SECTION_CHIEF"，見 DevDataSeeder 既有 seed 資料）。
     * 2026-08-14 原本刻意不處理 department／manager，因為當時 EMP 的服務單位/部室別代碼
     * 跟本專案既有的示範部門（TAIPEI_BRANCH／HQ_OPERATIONS…）沒有對應關係。
     * 2026-08-17 更新：現在有真正的部室代號參考表（{@link DeptCodeLookupService}）了，
     * 補上 department／serviceUnitCode／subDeptCode／subDeptName，
     * 否則 WorkflowEngine 動態直屬主管解析（見 WorkflowEngineServiceImpl#directSupervisorsOf）
     * 一定會因為缺資料而丟例外，讓 EMP 登入帳號完全無法送出表單。
     * Department 依服務單位代碼找不到就自動建立一筆（deptCode=服務單位、deptName=部室代號表的單位名稱），
     * 每個服務單位各自一筆，不共用同一筆——避免影響既有 ApproverType.DEPARTMENT 步驟
     * （GA／ACCOUNTING／AUDIT 專用，跟這裡的真實服務單位 Department 是兩回事，見 seed workflow JSON）。
     * 部室代號參考表查無對應資料時**不擋登入**（用 DeptCodeLookupService 的 find* 容忍查無資料、
     * 只記警告 log），因為這是真實登入路徑，參考表可能還沒收錄到所有服務單位/部室別組合——
     * 跟 DevDataSeeder 的 demo 資料（查無資料直接丟例外）風險等級不同，見 DeptCodeLookupService 說明。
     * serviceUnitCode／subDeptCode 這兩個 WorkflowEngine 動態解析真正需要的代碼一律照樣設定，
     * 不受名稱查詢失敗影響。
     * 掛角色時依 {@link #resolveRoleCode} 換算實際角色代碼——見該方法說明（分行沒有「科長」，
     * 分行的襄理等同總行的科長，2026-08-17 使用者澄清）。
     *
     * 2026-08-17 再更新（使用者提問「EMP 調部門 app_user 不會跟著變，要怎麼修正」）：
     * 帳號已存在時**不再直接跳過**，改成用這次查到的最新 EMP 資料覆寫
     * displayName／department／serviceUnitCode／subDeptCode／subDeptName／roles——
     * 讓 app_user 每次登入、或每次被 {@link #ensureAppUsersForServiceUnit} 掃到，
     * 都會用 EMP 最新資料校正一次，不會停留在第一次建立當下的舊部門/職級。
     * status 只有新建立時設成 ACTIVE，既有帳號不動這個欄位（目前 EMP 沒有離職/停用旗標可以同步，
     * 沒有依據可以幫既有帳號改 status，不要用「查得到 EMP 資料」去猜測誰還在職）。
     */
    @Transactional
    User ensureAppUser(LoginResponse identity) {
        User user = userRepository.findByAccount(identity.employeeCode()).orElseGet(User::new);
        boolean isNew = user.getId() == null;

        String subDeptCode = identity.deptCode() == null || identity.deptCode().isEmpty() ? null : identity.deptCode();

        user.setAccount(identity.employeeCode());
        user.setDisplayName(identity.name());
        if (isNew) {
            user.setStatus(UserStatus.ACTIVE);
        }
        user.setDepartment(resolveRealDepartment(identity.serviceUnit(), identity.headOffice()));
        user.setServiceUnitCode(identity.serviceUnit());
        user.setSubDeptCode(subDeptCode);
        user.setSubDeptName(deptCodeLookupService.findSubDeptName(identity.serviceUnit(), subDeptCode)
                .orElseGet(() -> {
                    log.warn("部室代號參考表查無「服務單位={}、部室別={}」，行員 {} 的 subDeptName 先留空",
                            identity.serviceUnit(), subDeptCode, identity.employeeCode());
                    return null;
                }));
        roleRepository.findByRoleCode(resolveRoleCode(identity.rank(), identity.headOffice()))
                .ifPresent(role -> {
                    // 就地清空再加入，不可用 setRoles(Set.of(role)) 整個替換成不可變集合：
                    // 已存在的 app_user 是 managed entity，userRepository.save() 會走 merge()，
                    // Hibernate 需要對 collection 欄位呼叫 clear() 來同步內容，
                    // 不可變集合的 clear() 會丟 UnsupportedOperationException。
                    user.getRoles().clear();
                    user.getRoles().add(role);
                });
        return userRepository.save(user);
    }

    /**
     * 查 EMP 該服務單位下所有人，逐一 {@link #ensureAppUser} 確保都已建立且資料是最新的
     * （已存在的帳號會被同步更新，不是跳過），供 WorkflowEngine 動態直屬主管解析的補建 fallback
     * 使用（見 {@link LoginService#ensureAppUsersForServiceUnit} 說明），順便也讓整個服務單位
     * 的人跟著這次呼叫一起校正一次資料，不只是原本觸發 fallback 的那一位。
     */
    @Override
    @Transactional
    public List<User> ensureAppUsersForServiceUnit(String serviceUnitCode) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(SQL_BY_SERVICE_UNIT, serviceUnitCode);
        return rows.stream()
                .map(this::toResponse)
                .map(this::ensureAppUser)
                .toList();
    }

    /**
     * EMP「主管別」推導出的 {@link EmpRank} 不分分行/總行，一律只有 4 級（經辦/科長/副理/協理）；
     * 但 WorkflowEngine 的分行職級鏈用的是「經辦/襄理/副理/協理」，沒有 SECTION_CHIEF 這個角色
     * （見 WorkflowEngineServiceImpl#BRANCH_RANK_LADDER）。使用者澄清（2026-08-17）：
     * 「分行的襄理等於總行的科長，分行沒有科長只有襄理」——即同一個職級位階，只是分行/總行叫法不同。
     * 因此分行使用者若職級推導為 SECTION_CHIEF，實際要掛的角色代碼要換成 BRANCH_ASSISTANT_MANAGER；
     * 其餘三級（HANDLER／DEPUTY_MANAGER／DIRECTOR）分行/總行共用同一角色代碼，不用換算。
     * 注意：這只影響「實際掛給使用者的角色代碼」，{@link LoginResponse#rank()} 本身的值不變
     * （仍然是依「主管別」字典序推導出的原始分類，語意上與分行/總行無關，見 resolveRank）。
     */
    static String resolveRoleCode(EmpRank rank, boolean headOffice) {
        if (rank == EmpRank.SECTION_CHIEF && !headOffice) {
            return "BRANCH_ASSISTANT_MANAGER";
        }
        return rank.name();
    }

    private Department resolveRealDepartment(String serviceUnitCode, boolean headOffice) {
        return departmentRepository.findByDeptCode(serviceUnitCode)
                .orElseGet(() -> {
                    Department department = new Department();
                    department.setDeptCode(serviceUnitCode);
                    department.setDeptName(deptCodeLookupService.findUnitName(serviceUnitCode)
                            .orElseGet(() -> {
                                log.warn("部室代號參考表查無服務單位={} 的單位名稱，Department.deptName 先用代碼本身代替",
                                        serviceUnitCode);
                                return serviceUnitCode;
                            }));
                    department.setDeptType(headOffice ? DeptType.HEAD_OFFICE : DeptType.BRANCH);
                    return departmentRepository.save(department);
                });
    }

    /**
     * 依「主管別」字串比較（字典序，非數值）判斷職級（人工確認規則，2026-08-14）：
     * 主管別="Z" → 經辦；"H"&lt;主管別&lt;="I" → 副理；"I"&lt;主管別&lt;"Z" → 科長；主管別&lt;="H" → 協理。
     * EMP 表目前實際資料涵蓋 "A".."Z"，字典序大於 "Z" 的代碼不在已確認規則範圍內，
     * 視為資料異常直接丟例外，避免默默誤判職級（職級會影響簽核權限判斷，不可猜測帶過）。
     */
    static EmpRank resolveRank(String managerCode) {
        if (managerCode == null || managerCode.isEmpty()) {
            throw new IllegalStateException("主管別為空，無法判斷職級");
        }
        if ("Z".equals(managerCode)) {
            return EmpRank.HANDLER;
        }
        if (managerCode.compareTo("H") <= 0) {
            return EmpRank.DIRECTOR;
        }
        if (managerCode.compareTo("I") <= 0) {
            return EmpRank.DEPUTY_MANAGER;
        }
        if (managerCode.compareTo("Z") < 0) {
            return EmpRank.SECTION_CHIEF;
        }
        throw new IllegalStateException("主管別超出已知規則範圍：" + managerCode);
    }

}
