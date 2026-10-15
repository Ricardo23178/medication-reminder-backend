package com.medreminder.medicationbackend.admin;

import com.medreminder.medicationbackend.common.Result;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;

/** Authentication and current database role are checked before every /api/admin request. */
@RestController
@RequestMapping("/api/admin")
public class AdminController {
    private final AdminService service;
    public AdminController(AdminService service) { this.service=service; }
    public record StatusRequest(@NotNull Integer status,@NotBlank @Size(min=3,max=200) String reason) {}
    public record SettingRequest(@NotNull @Size(max=4000) String value,@NotNull @Min(0) Integer version,
                                 @NotBlank @Size(min=3,max=200) String reason) {}
    @GetMapping("/me") public Object me() { return Result.ok(service.me()); }
    @GetMapping("/overview") public Object overview() { return Result.ok(service.overview()); }
    @GetMapping("/users") public Object users(@RequestParam(required=false) String keyword,@RequestParam(required=false) Integer status,
                                             @RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize) {
        return Result.ok(service.users(keyword,status,page,pageSize));
    }
    @PatchMapping("/users/{id}/status") public Object status(@PathVariable Long id,@Valid @RequestBody StatusRequest request) {
        return outcome(service.status(id,request.status(),request.reason()));
    }
    @GetMapping("/settings") public Object settings(@RequestParam(required=false) String category) { return Result.ok(service.settings(category)); }
    @PutMapping("/settings/{id}") public Object setting(@PathVariable Long id,@Valid @RequestBody SettingRequest request) {
        return outcome(service.updateSetting(id,request.value(),request.version(),request.reason()));
    }
    @GetMapping("/events") public Object events(@RequestParam(required=false) String status,@RequestParam(defaultValue="1") int page,
                                               @RequestParam(defaultValue="20") int pageSize) { return Result.ok(service.events(status,page,pageSize)); }
    @GetMapping("/notifications") public Object notifications(@RequestParam(required=false) String status,@RequestParam(defaultValue="1") int page,
                                                              @RequestParam(defaultValue="20") int pageSize) { return Result.ok(service.notifications(status,page,pageSize)); }
    @GetMapping("/audit-logs") public Object audits(@RequestParam(required=false) String action,@RequestParam(defaultValue="1") int page,
                                                   @RequestParam(defaultValue="20") int pageSize) { return Result.ok(service.audits(action,page,pageSize)); }
    private Object outcome(int code) {
        return code==200?Result.ok(null):Result.error(code,code==404?"目标不存在": "状态或配置版本已变化，或该账号不允许修改，请刷新确认");
    }
}
