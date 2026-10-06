package com.rag.api.application;

import com.rag.api.common.BizException;
import com.rag.api.common.TenantContext;
import com.rag.api.infrastructure.persistence.entity.KnowledgeBaseEntity;
import com.rag.api.infrastructure.persistence.entity.PromptTemplateEntity;
import com.rag.api.infrastructure.persistence.entity.SysUserEntity;
import com.rag.api.infrastructure.persistence.entity.UserFeatureEntity;
import com.rag.api.infrastructure.persistence.entity.UserKbEntity;
import com.rag.api.infrastructure.persistence.entity.UserPromptEntity;
import com.rag.api.infrastructure.persistence.mapper.KnowledgeBaseMapper;
import com.rag.api.infrastructure.persistence.mapper.PromptTemplateMapper;
import com.rag.api.infrastructure.persistence.mapper.SysMenuMapper;
import com.rag.api.infrastructure.persistence.mapper.SysToolMapper;
import com.rag.api.infrastructure.persistence.mapper.SysUserMapper;
import com.rag.api.infrastructure.persistence.mapper.TenantMenuMapper;
import com.rag.api.infrastructure.persistence.mapper.UserFeatureMapper;
import com.rag.api.infrastructure.persistence.mapper.UserKbMapper;
import com.rag.api.infrastructure.persistence.mapper.UserPromptMapper;
import com.rag.api.infrastructure.persistence.entity.SysMenuEntity;
import com.rag.api.infrastructure.persistence.entity.SysToolEntity;
import com.rag.api.infrastructure.persistence.entity.TenantMenuEntity;
import com.rag.api.interfaces.dto.Dtos;
import com.rag.api.interfaces.security.AuthGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Collectors;

/**
 * 租户内普通用户管理：账号生命周期、功能菜单授权（chat / image-studio）、
 * 知识库授权。仅租户管理员可操作。
 */
@Service
@RequiredArgsConstructor
public class UserManageService {

    public static final String TYPE_MENU = "MENU";
    public static final String TYPE_TOOL = "TOOL";

    /** 新建普通用户默认授权的功能菜单。 */
    private static final List<String> DEFAULT_MENUS = List.of("chat", "image-studio");
    private static final Random RANDOM = new SecureRandom();

    private final SysUserMapper userMapper;
    private final UserKbMapper userKbMapper;
    private final UserPromptMapper userPromptMapper;
    private final UserFeatureMapper userFeatureMapper;
    private final PromptTemplateMapper promptMapper;
    private final KnowledgeBaseMapper kbMapper;
    private final SysMenuMapper sysMenuMapper;
    private final SysToolMapper sysToolMapper;
    private final TenantMenuMapper tenantMenuMapper;
    private final PasswordEncoder passwordEncoder;

    public List<Dtos.UserView> list() {
        AuthGuard.requireTenantAdmin();
        TenantContext.Session s = TenantContext.require();
        List<SysUserEntity> users = userMapper.selectList(s.tenantId(), null, null, 2);
        if (users.isEmpty()) {
            return List.of();
        }
        List<Long> userIds = users.stream().map(SysUserEntity::getId).toList();

        // 批量预加载，避免逐用户 1+N：菜单/工具一条 IN 查回，内存按类型拆分。
        Map<Long, List<UserFeatureEntity>> features = userFeatureMapper.selectByUserIds(userIds).stream()
                .collect(Collectors.groupingBy(UserFeatureEntity::getUserId));
        Map<Long, List<Long>> kbIdsByUser = userKbMapper.selectByUserIds(userIds).stream()
                .collect(Collectors.groupingBy(UserKbEntity::getUserId,
                        Collectors.mapping(UserKbEntity::getKbId, Collectors.toList())));
        Map<Long, List<Long>> promptIdsByUser = userPromptMapper.selectByUserIds(userIds).stream()
                .collect(Collectors.groupingBy(UserPromptEntity::getUserId,
                        Collectors.mapping(UserPromptEntity::getPromptId, Collectors.toList())));

        return users.stream().map(u -> {
            List<UserFeatureEntity> f = features.getOrDefault(u.getId(), List.of());
            List<String> menus = f.stream()
                    .filter(x -> TYPE_MENU.equals(x.getFeatureType()))
                    .map(UserFeatureEntity::getCode).toList();
            List<String> tools = f.stream()
                    .filter(x -> TYPE_TOOL.equals(x.getFeatureType()))
                    .map(UserFeatureEntity::getCode).toList();
            return buildView(u, menus, tools,
                    kbIdsByUser.getOrDefault(u.getId(), List.of()),
                    promptIdsByUser.getOrDefault(u.getId(), List.of()));
        }).toList();
    }

    @Transactional
    public Dtos.UserCredentialResp create(Dtos.UserCreateReq req) {
        AuthGuard.requireTenantAdmin();
        TenantContext.Session s = TenantContext.require();
        String username = req.username().trim();
        if (username.length() < 2 || username.length() > 50) {
            throw BizException.badRequest("账号长度需在 2 ~ 50 位之间");
        }
        if (userMapper.countByTenantIdAndUsername(s.tenantId(), username) > 0) {
            throw BizException.badRequest("账号已存在");
        }
        String initialPassword = randomPassword();
        SysUserEntity user = new SysUserEntity();
        user.setTenantId(s.tenantId());
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode(initialPassword));
        user.setUserType(2);
        user.setStatus(1);
        userMapper.insert(user);
        replaceFeatures(user.getId(), s.tenantId(), TYPE_MENU, DEFAULT_MENUS);
        return new Dtos.UserCredentialResp(toView(user), initialPassword);
    }

    @Transactional
    public Dtos.UserView update(long id, Dtos.UpdateUserReq req) {
        AuthGuard.requireTenantAdmin();
        SysUserEntity user = requireOwned(id);
        if (req.status() != null) {
            if (req.status() != 0 && req.status() != 1) {
                throw BizException.badRequest("状态值非法");
            }
            user.setStatus(req.status());
        }
        if (req.menuCodes() != null) {
            List<String> menus = req.menuCodes().stream().distinct().toList();
            if (menus.stream().anyMatch(m -> !grantableMenuCodes().contains(m))) {
                throw BizException.badRequest("存在非法的功能菜单码");
            }
            replaceFeatures(user.getId(), user.getTenantId(), TYPE_MENU, menus);
        }
        if (req.toolCodes() != null) {
            List<String> tools = req.toolCodes().stream().distinct().toList();
            if (tools.stream().anyMatch(t -> !enabledToolCodes().contains(t))) {
                throw BizException.badRequest("存在非法的工具码");
            }
            replaceFeatures(user.getId(), user.getTenantId(), TYPE_TOOL, tools);
        }
        userMapper.updateById(user);
        return toView(user);
    }

    @Transactional
    public Dtos.ResetUserPasswordResp resetPassword(long id) {
        AuthGuard.requireTenantAdmin();
        SysUserEntity user = requireOwned(id);
        String initialPassword = randomPassword();
        user.setPasswordHash(passwordEncoder.encode(initialPassword));
        userMapper.updateById(user);
        return new Dtos.ResetUserPasswordResp(user.getId(), initialPassword);
    }

    public List<Long> grantedKbIds(long userId) {
        AuthGuard.requireTenantAdmin();
        requireOwned(userId);
        return listKbIds(userId);
    }

    @Transactional
    public void grantKbs(long userId, List<Long> kbIds, List<Long> promptIds) {
        AuthGuard.requireTenantAdmin();
        TenantContext.Session s = TenantContext.require();
        requireOwned(userId);
        List<Long> ids = kbIds == null ? List.of() : kbIds.stream().distinct().toList();
        if (!ids.isEmpty()) {
            List<KnowledgeBaseEntity> kbs = kbMapper.selectByIds(ids);
            if (kbs.size() != ids.size() || kbs.stream().anyMatch(k -> !s.tenantId().equals(k.getTenantId()))) {
                throw BizException.badRequest("存在不属于本租户的知识库");
            }
        }
        List<Long> pids = promptIds == null ? List.of() : promptIds.stream().distinct().toList();
        if (!pids.isEmpty()) {
            List<PromptTemplateEntity> prompts = promptMapper.selectByIds(pids);
            if (prompts.size() != pids.size()
                    || prompts.stream().anyMatch(p -> !s.tenantId().equals(p.getTenantId()))
                    || prompts.stream().anyMatch(p -> !ids.contains(p.getKbId()))) {
                throw BizException.badRequest("存在未授权知识库下的提示词");
            }
        }
        userKbMapper.deleteByUserId(userId);
        userPromptMapper.deleteByUserId(userId);
        for (Long kbId : ids) {
            UserKbEntity e = new UserKbEntity();
            e.setTenantId(s.tenantId());
            e.setUserId(userId);
            e.setKbId(kbId);
            userKbMapper.insert(e);
        }
        for (Long pid : pids) {
            UserPromptEntity e = new UserPromptEntity();
            e.setTenantId(s.tenantId());
            e.setUserId(userId);
            e.setPromptId(pid);
            userPromptMapper.insert(e);
        }
    }

    /** 知识库删除时清理其用户授权关系。 */
    @Transactional
    public void onKbDeleted(long kbId) {
        userKbMapper.deleteByKbId(kbId);
        List<Long> promptIds = promptMapper.selectIdsByKbId(kbId);
        if (!promptIds.isEmpty()) {
            userPromptMapper.deleteByPromptIds(promptIds);
        }
    }

    /** 普通用户提示词授权校验；管理员不限制。 */
    public void requirePrompt(long promptId) {
        TenantContext.Session s = TenantContext.require();
        if (s.userType() != 2) {
            return;
        }
        if (userPromptMapper.countByUserIdAndPromptId(s.userId(), promptId) == 0) {
            throw BizException.forbidden("未获得该提示词模板的使用授权");
        }
    }

    /** 指定知识库下当前用户可用的提示词：普通用户=被授权的，管理员=全部。 */
    public List<PromptTemplateEntity> visiblePrompts(long kbId) {
        TenantContext.Session s = TenantContext.require();
        if (s.userType() == 2) {
            List<Long> pids = userPromptMapper.selectByUserId(s.userId()).stream()
                    .map(UserPromptEntity::getPromptId).toList();
            if (pids.isEmpty()) {
                return List.of();
            }
            return promptMapper.selectByKbIdAndIds(kbId, pids);
        }
        return promptMapper.selectByKbId(kbId);
    }

    /**
     * 普通用户访问功能菜单的后端鉴权；管理员不限制。
     * 三层强制：目录存在且启用 → 租户总开关开启 → 个人已授权。
     */
    public void requireMenu(String code) {
        TenantContext.Session s = TenantContext.require();
        if (s.userType() != 2) {
            return;
        }
        SysMenuEntity menu = sysMenuMapper.selectByCode(code);
        if (menu == null || !Boolean.TRUE.equals(menu.getStatus())
                || !Boolean.TRUE.equals(menu.getEndUser())) {
            throw BizException.forbidden("未获得该功能的使用授权");
        }
        TenantMenuEntity tenantMenu = tenantMenuMapper.selectOneByTenantIdAndMenuCode(s.tenantId(), code);
        if (tenantMenu == null || !Boolean.TRUE.equals(tenantMenu.getEnabled())) {
            throw BizException.forbidden("未获得该功能的使用授权");
        }
        if (userFeatureMapper.countByUserIdAndTypeAndCode(s.userId(), TYPE_MENU, code) == 0) {
            throw BizException.forbidden("未获得该功能的使用授权");
        }
    }

    /**
     * 知识库访问的后端鉴权：知识库必须存在、属于当前租户，
     * 普通用户还须已在 user_kb 中被授权；平台/租户管理员不做个人授权限制。
     */
    public KnowledgeBaseEntity requireKbAccess(long kbId) {
        TenantContext.Session s = TenantContext.require();
        KnowledgeBaseEntity kb = kbMapper.selectById(kbId);
        if (kb == null) {
            throw BizException.notFound("知识库不存在");
        }
        if (!s.isPlatformAdmin() && !s.tenantId().equals(kb.getTenantId())) {
            throw BizException.forbidden("无权访问该知识库");
        }
        if (s.userType() == 2
                && userKbMapper.countByUserIdAndKbId(s.userId(), kbId) == 0) {
            throw BizException.forbidden("未获得该知识库的使用授权");
        }
        return kb;
    }

    /** 当前用户可见知识库：普通用户=被授权的，管理员=本租户全部。 */
    public List<KnowledgeBaseEntity> visibleKbs() {
        TenantContext.Session s = TenantContext.require();
        if (s.userType() == 2) {
            List<Long> ids = listKbIds(s.userId());
            return ids.isEmpty() ? List.of() : kbMapper.selectByIds(ids);
        }
        return kbMapper.selectByTenantId(s.tenantId());
    }

    /** 用户个人菜单码（登录信息下发用）。 */
    public List<String> menuCodesOf(long userId) {
        return listFeatureCodes(userId, TYPE_MENU);
    }

    /**
     * 工具最终可用性：租户总开关 + 普通用户个人授权（管理员不做个人限制）。
     */
    public boolean toolAllowed(String code, boolean tenantEnabled) {
        if (!tenantEnabled) {
            return false;
        }
        TenantContext.Session s = TenantContext.require();
        if (s.userType() != 2) {
            return true;
        }
        return userFeatureMapper.countByUserIdAndTypeAndCode(s.userId(), TYPE_TOOL, code) > 0;
    }

    /** 普通用户可授权的功能菜单码（目录启用）。 */
    private List<String> grantableMenuCodes() {
        return sysMenuMapper.selectList(true, null, null, true).stream()
                .map(SysMenuEntity::getCode).toList();
    }

    /** 产品启用的工具码目录。 */
    private List<String> enabledToolCodes() {
        return sysToolMapper.selectListByStatus(true).stream()
                .map(SysToolEntity::getCode).toList();
    }

    /** 全量替换某用户某类功能授权。 */
    private void replaceFeatures(long userId, String tenantId, String type, List<String> codes) {
        userFeatureMapper.deleteByUserIdAndType(userId, type);
        for (String code : codes) {
            UserFeatureEntity e = new UserFeatureEntity();
            e.setTenantId(tenantId);
            e.setUserId(userId);
            e.setFeatureType(type);
            e.setCode(code);
            userFeatureMapper.insert(e);
        }
    }

    private List<String> listFeatureCodes(long userId, String type) {
        return userFeatureMapper.selectListByUserIdAndType(userId, type).stream()
                .map(UserFeatureEntity::getCode).toList();
    }

    private List<Long> listKbIds(long userId) {
        return userKbMapper.selectByUserId(userId).stream()
                .map(UserKbEntity::getKbId).toList();
    }

    private SysUserEntity requireOwned(long id) {
        TenantContext.Session s = TenantContext.require();
        SysUserEntity user = userMapper.selectById(id);
        if (user == null || !s.tenantId().equals(user.getTenantId()) || user.getUserType() != 2) {
            throw BizException.notFound("用户不存在");
        }
        return user;
    }

    private Dtos.UserView toView(SysUserEntity user) {
        return buildView(user,
                listFeatureCodes(user.getId(), TYPE_MENU),
                listFeatureCodes(user.getId(), TYPE_TOOL),
                listKbIds(user.getId()), listPromptIds(user.getId()));
    }

    private Dtos.UserView buildView(SysUserEntity user, List<String> menuCodes,
                                    List<String> toolCodes, List<Long> kbIds,
                                    List<Long> promptIds) {
        return new Dtos.UserView(user.getId(), user.getUsername(),
                user.getStatus() == null ? 0 : user.getStatus(),
                menuCodes, toolCodes, kbIds, promptIds);
    }

    private List<Long> listPromptIds(long userId) {
        return userPromptMapper.selectByUserId(userId).stream()
                .map(UserPromptEntity::getPromptId).toList();
    }

    private String randomPassword() {
        String chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789";
        StringBuilder sb = new StringBuilder(10);
        for (int i = 0; i < 10; i++) {
            sb.append(chars.charAt(RANDOM.nextInt(chars.length())));
        }
        return sb.toString();
    }
}
