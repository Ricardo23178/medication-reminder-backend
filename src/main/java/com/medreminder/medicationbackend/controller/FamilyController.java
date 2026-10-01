package com.medreminder.medicationbackend.controller;

import com.medreminder.medicationbackend.common.Result;
import com.medreminder.medicationbackend.common.UserContext;
import com.medreminder.medicationbackend.dto.AcceptInviteRequest;
import com.medreminder.medicationbackend.dto.InviteRequest;
import com.medreminder.medicationbackend.service.FamilyService;
import com.medreminder.medicationbackend.vo.AbnormalEventVO;
import com.medreminder.medicationbackend.vo.FamilyRelationVO;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 家属协同接口 —— 导师方案接口清单第 7、8、9 个，外加补充的「接受邀请」。
 *   POST   /api/family/invitations            发起绑定邀请（owner，生成邀请码）
 *   POST   /api/family/invitations/accept     家属凭邀请码接受绑定（补充接口，闭环必需）
 *   GET    /api/family/relations              查我相关的所有关系
 *   DELETE /api/family/relations/{id}         解除关系（owner 单方）
 *   GET    /api/family/abnormal-events        家属查看被授权对象的异常事件
 *
 * 说明：导师清单只列了 invitations / relations/{id} / abnormal-events 三个，
 * 但缺「接受邀请」会导致绑定永远停在 PENDING、闭环断掉，故补 accept。
 */
@RestController
@RequestMapping("/api/family")
public class FamilyController {

    private final FamilyService familyService;

    public FamilyController(FamilyService familyService) {
        this.familyService = familyService;
    }

    /** 发起邀请（owner） */
    @PostMapping("/invitations")
    public Result<FamilyRelationVO> invite(@Valid @RequestBody InviteRequest req) {
        return Result.ok(familyService.invite(UserContext.userId(), req));
    }

    /** 家属凭邀请码接受绑定（补充接口） */
    @PostMapping("/invitations/accept")
    public Result<FamilyRelationVO> accept(@Valid @RequestBody AcceptInviteRequest req) {
        Long familyUserId = UserContext.userId();
        int code = familyService.accept(familyUserId, req);

        return switch (code) {
            case 0 -> {
                // 成功：回查该关系返回（此时 familyUserId 已填，展示双方昵称）
                FamilyRelationVO vo = familyService.listMine(familyUserId).stream()
                        .filter(r -> "ACTIVE".equals(r.getRelationStatus()))
                        .findFirst()
                        .orElse(null);
                yield Result.ok(vo);
            }
            case 1 -> Result.error(400, "邀请码不存在");
            case 2 -> Result.error(400, "邀请码已过期");
            case 3 -> Result.error(400, "不能绑定自己");
            default -> Result.error(400, "邀请已被处理，请勿重复操作");
        };
    }

    /** 查我相关的所有关系（owner + family 双视角） */
    @GetMapping("/relations")
    public Result<List<FamilyRelationVO>> relations() {
        return Result.ok(familyService.listMine(UserContext.userId()));
    }

    /** 解除关系（owner 单方） */
    @DeleteMapping("/relations/{id}")
    public Result<Void> unbind(@PathVariable Long id) {
        boolean ok = familyService.unbind(UserContext.userId(), id);
        if (!ok) {
            return Result.error(403, "关系不存在、已解除或无权操作");
        }
        return Result.ok(null);
    }

    /** 家属查看被授权对象的异常事件 */
    @GetMapping("/abnormal-events")
    public Result<List<AbnormalEventVO>> abnormalEvents() {
        return Result.ok(familyService.listAuthorizedAbnormalEvents(UserContext.userId()));
    }
}
