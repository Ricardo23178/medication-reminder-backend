package com.medreminder.medicationbackend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 新增 / 修改药品档案的入参。
 * name 必填（不填没意义），其余选填。用 @NotBlank/@Size 做参数校验，
 * 不合法直接 400，不进入业务层。
 */
@Data
public class MedicineRequest {

    /** 药品名称，必填 */
    @NotBlank(message = "药品名称不能为空")
    @Size(max = 128, message = "药品名称过长")
    private String name;

    /** 规格，如 5mg×30片 */
    @Size(max = 64, message = "规格过长")
    private String specification;

    /** 单次剂量，如 1片 */
    @Size(max = 64, message = "剂量过长")
    private String dosage;

    /** 剂量单位 */
    @Size(max = 16, message = "单位过长")
    private String unit;

    /** 用户备注 */
    @Size(max = 255, message = "备注过长")
    private String remark;

    /** 药盒照片地址 */
    @Size(max = 255, message = "图片地址过长")
    private String imageUrl;

    /** 1=启用, 0=停用 */
    private Integer enabled;
}
