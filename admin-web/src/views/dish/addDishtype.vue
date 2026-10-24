<template>
  <div :key="vueRest"
       class="addBrand-container"
  >
    <div :key="restKey"
         class="container"
    >
      <el-form ref="ruleForm"
               :model="ruleForm"
               :rules="rules"
               label-width="160px"
               class="demo-ruleForm dish-form"
      >
        <div class="form-grid form-grid--two">
          <el-form-item label="菜品名称:"
                        prop="name"
          >
            <el-input v-model="ruleForm.name"
                      placeholder="请填写菜品名称"
                      maxlength="20"
            />
          </el-form-item>
          <el-form-item label="菜品分类:"
                        prop="categoryId"
          >
            <el-select v-model="ruleForm.categoryId"
                       placeholder="请选择菜品分类"
            >
              <el-option v-for="(item, index) in dishList"
                         :key="index"
                         :label="item.name"
                         :value="item.id"
              />
            </el-select>
          </el-form-item>
        </div>
        <div class="form-grid">
          <el-form-item label="菜品价格:"
                        prop="price"
          >
            <el-input v-model="ruleForm.price"
                      placeholder="请设置菜品价格"
            />
          </el-form-item>
        </div>
        <el-form-item label="口味做法配置:"
                      class="flavor-form-item"
        >
          <div class="flavorBox">
            <div v-if="dishFlavors.length" class="flavor-list">
              <div v-for="(item, index) in dishFlavors"
                   :key="index"
                   class="flavor-row"
              >
                <el-input
                  v-model="item.name"
                  class="flavor-name-input"
                  placeholder="口味名称"
                  maxlength="20"
                  clearable
                />
                <div class="flavor-options">
                  <el-tag
                    v-for="(option, optionIndex) in item.value"
                    :key="`${option}-${optionIndex}`"
                    closable
                    type="success"
                    @close="delFlavorLabel(index, optionIndex)"
                  >
                    {{ option }}
                  </el-tag>
                  <el-input
                    v-model="item.inputValue"
                    class="flavor-option-input"
                    placeholder="输入选项后按 Enter 添加"
                    maxlength="30"
                    @keyup.enter.stop="addFlavorLabel(index, item)"
                  />
                </div>
                <el-button
                  type="danger"
                  plain
                  class="delete-flavor-button"
                  @click="delFlavor(index)"
                >
                  删除
                </el-button>
              </div>
            </div>
            <el-button type="primary" plain @click="addFlavore">
              + 添加口味
            </el-button>
            <p class="flavor-hint">口味名称和可选项均可自定义；在选项输入框中按 Enter 添加。</p>
          </div>
        </el-form-item>
        <el-form-item label="菜品图片:"
                      prop="image"
                      class="image-form-item"
        >
          <image-upload :prop-image-url="imageUrl"
                        @imageChange="imageChange"
          >
            图片大小不超过2M<br>仅能上传 PNG JPEG JPG类型图片<br>建议上传200*200或300*300尺寸的图片
          </image-upload>
        </el-form-item>
        <el-form-item label="菜品描述:"
                      prop="description"
                      class="description-form-item"
        >
          <el-input v-model="ruleForm.description"
                    type="textarea"
                    :rows="3"
                    maxlength="200"
                    placeholder="菜品描述，最长200字"
          />
        </el-form-item>
        <div class="subBox">
          <el-button @click="() => $router.back()">
            取消
          </el-button>
          <el-button type="primary"
                     :class="{ continue: actionType === 'add' }"
                     @click="submitForm('ruleForm')"
          >
            保存
          </el-button>
          <el-button v-if="actionType == 'add'"
                     type="primary"
                     @click="submitForm('ruleForm', 'goAnd')"
          >
            保存并继续添加
          </el-button>
        </div>
      </el-form>
    </div>
  </div>
</template>

<script lang="ts">
import { Component, Vue } from 'vue-facing-decorator'
import HeadLable from '@/components/HeadLable/index.vue'
import ImageUpload from '@/components/ImgUpload/index.vue'
// getFlavorList口味列表暂时不做 getDishTypeList
import {
  queryDishById,
  addDish,
  editDish,
  getCategoryList,
  commonDownload
} from '@/api/dish'
import { baseUrl } from '@/config.json'
import { getToken } from '@/utils/cookies'
@Component({
  name: 'addShop',
  components: {
    HeadLable,
    ImageUpload
  }
})
export default class AddShop extends Vue {
  private restKey: number = 0
  private textarea: string = ''
  private value: string = ''
  private imageUrl: string = ''
  private actionType: string = ''
  private dishList: string[] = []
  private dishFlavors: any[] = [] //待上传口味的数据
  private vueRest = '1'
  private headers = {
    token: getToken()
  }
  private ruleForm = {
    name: '',
    id: '',
    price: '',
    code: '',
    image: '',
    description: '',
    dishFlavors: [],
    status: true,
    categoryId: ''
  }

  get rules() {
    return {
      name: [
        {
          required: true,
          validator: (rule: any, value: string, callback: (error?: Error) => void) => {
            if (!value) {
              callback(new Error('请输入菜品名称'))
            } else {
              const reg = /^([A-Za-z0-9\u4e00-\u9fa5]){2,20}$/
              if (!reg.test(value)) {
                callback(new Error('菜品名称输入不符，请输入2-20个字符'))
              } else {
                callback()
              }
            }
          },
          trigger: 'blur'
        }
      ],
      categoryId: [
        { required: true, message: '请选择菜品分类', trigger: 'change' }
      ],
      image: {
        required: true,
        message: '菜品图片不能为空'
      },
      price: [
        {
          required: true,
          // 'message': '请填写菜品价格',
          validator: (rules: any, value: string, callback: (error?: Error) => void) => {
            const reg = /^([1-9]\d{0,5}|0)(\.\d{1,2})?$/
            if (!reg.test(value) || Number(value) <= 0) {
              callback(
                new Error(
                  '菜品价格格式有误，请输入大于零且最多保留两位小数的金额'
                )
              )
            } else {
              callback()
            }
          },
          trigger: 'blur'
        }
      ],
      code: [{ required: true, message: '请填写商品码', trigger: 'blur' }]
    }
  }

  created() {
    this.getDishList()
    this.actionType = this.$route.query.id ? 'edit' : 'add'
    if (this.$route.query.id) {
      this.init()
    }
  }

  mounted() {}

  private async init() {
    queryDishById(this.$route.query.id).then(res => {
      if (res && res.data && res.data.code === 1) {
        this.ruleForm = { ...res.data.data }
        this.ruleForm.price = String(res.data.data.price)
        this.ruleForm.status = res.data.data.status == '1'
        this.dishFlavors = (res.data.data.flavors ?? []).map(obj => ({
            ...obj,
            value: JSON.parse(obj.value),
            inputValue: ''
          }))
        this.imageUrl = res.data.data.image
      } else {
        this.$message.error(res.data.msg)
      }
    })
  }

  // 按钮 - 添加口味
  private addFlavore() {
    this.dishFlavors.push({ name: '', value: [], inputValue: '' })
  }

  // 按钮 - 删除口味
  private delFlavor(index: number) {
    this.dishFlavors.splice(index, 1)
  }

  // 按钮 - 删除口味标签
  private delFlavorLabel(index: number, ind: number) {
    this.dishFlavors[index].value.splice(ind, 1)
  }

  private addFlavorLabel(index: number, item: any) {
    const value = String(item.inputValue || '').trim()
    if (!value) return
    if (!Array.isArray(item.value)) item.value = []
    if (!item.value.includes(value)) item.value.push(value)
    item.inputValue = ''
  }

  // 获取菜品分类
  private getDishList() {
    getCategoryList({ type: 1 }).then(res => {
      if (res.data.code === 1) {
        this.dishList = res && res.data && res.data.data
      } else {
        this.$message.error(res.data.msg)
      }
      // if (res.data.code == 200) {
      //   const {data} = res.data
      //   this.dishList = data
      // } else {
      //   this.$message.error(res.data.desc)
      // }
    })
  }

  private submitForm(formName: any, st: any) {
    (this.$refs[formName] as any).validate((valid: any) => {
      console.log(valid, 'valid')
      if (valid) {
        if (!this.ruleForm.image) return this.$message.error('菜品图片不能为空')
        let params: any = { ...this.ruleForm }
        // params.flavors = this.dishFlavors
        params.status =
          this.actionType === 'add' ? 0 : this.ruleForm.status ? 1 : 0
        // params.price *= 100
        params.categoryId = this.ruleForm.categoryId
        params.flavors = this.dishFlavors.map(obj => ({
          name: obj.name,
          value: JSON.stringify(obj.value)
        }))
        delete params.dishFlavors
        if (this.actionType == 'add') {
          delete params.id
          addDish(params)
            .then(res => {
              if (res.data.code === 1) {
                this.$message.success('菜品添加成功！')
                if (!st) {
                  this.$router.push({ path: '/dish' })
                } else {
                  this.dishFlavors = []
                  // this.dishFlavorsData = []
                  this.imageUrl = ''
                  this.ruleForm = {
                    name: '',
                    id: '',
                    price: '',
                    code: '',
                    image: '',
                    description: '',
                    dishFlavors: [],
                    status: true,
                    categoryId: ''
                  }
                  this.restKey++
                }
              } else {
                this.$message.error(res.data.desc || res.data.msg)
              }
            })
            .catch(err => {
              this.$message.error('请求出错了：' + err.message)
            })
        } else {
          delete params.createTime
          delete params.updateTime
          editDish(params)
            .then(res => {
              if (res && res.data && res.data.code === 1) {
                this.$router.push({ path: '/dish' })
                this.$message.success('菜品修改成功！')
              } else {
                this.$message.error(res.data.desc || res.data.msg)
              }
              // if (res.data.code == 200) {
              //   this.$router.push({'path': '/dish'})
              //   this.$message.success('菜品修改成功！')
              // } else {
              //   this.$message.error(res.data.desc || res.data.message)
              // }
            })
            .catch(err => {
              this.$message.error('请求出错了：' + err.message)
            })
        }
      } else {
        return false
      }
    })
  }

  imageChange(value: any) {
    this.ruleForm.image = value
  }
}
</script>
<style lang="scss" scoped>
.addBrand-container {
  margin: 24px;

  .container {
    max-width: 1180px;
    padding: 32px;
    border: 1px solid #edf0ed;
    border-radius: 10px;
    background: #fff;
    box-shadow: 0 8px 24px rgb(25 38 30 / 5%);
  }
}
.dish-form {
  max-width: 1060px;

  :deep(.el-form-item) {
    margin-bottom: 22px;
  }

  :deep(.el-form-item__label) {
    color: #45534a;
    font-weight: 600;
    line-height: 40px;
  }

  :deep(.el-form-item__content) {
    min-width: 0;
    line-height: 40px;
  }

  :deep(.el-input),
  :deep(.el-select) {
    width: 100%;
    max-width: 420px;
  }

  .form-grid {
    display: grid;
    grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
    column-gap: 28px;
  }

  .form-grid--two :deep(.el-form-item) {
    min-width: 0;
  }

  .flavor-form-item,
  .image-form-item,
  .description-form-item {
    width: 100%;
  }

  .flavor-form-item :deep(.el-form-item__content),
  .description-form-item :deep(.el-form-item__content) {
    width: min(100%, 820px);
  }

  .image-form-item :deep(.el-form-item__content) {
    width: min(100%, 820px);
  }

  .description-form-item :deep(.el-textarea) {
    width: 100%;
    max-width: 820px;
  }

  .image-form-item :deep(.upload-item) {
    display: grid;
    grid-template-columns: 160px minmax(0, 1fr);
    align-items: center;
    column-gap: 12px;
    width: min(100%, 540px);
  }

  .image-form-item :deep(.avatar-uploader),
  .image-form-item :deep(.avatar-uploader .el-upload) {
    width: 160px;
    height: 120px;
    box-sizing: border-box;
  }

  .image-form-item :deep(.avatar-uploader .el-upload) {
    position: relative;
    display: flex;
    align-items: center;
    justify-content: center;
    overflow: hidden;
    border: 1px dashed #cbd5ce;
    border-radius: 8px;
    background: #f8faf8;
  }

  .image-form-item :deep(.avatar-uploader .avatar-uploader-icon::after) {
    top: 50%;
    left: 50%;
    transform: translate(-50%, -50%);
  }

  .image-form-item :deep(.avatar-uploader-icon),
  .image-form-item :deep(.avatar) {
    width: 160px;
    height: 120px;
  }

  .image-form-item :deep(.avatar-uploader-icon) {
    line-height: 120px;
  }

  .image-form-item :deep(.upload-tips) {
    min-width: 0;
    margin: 0;
    color: #737e76;
    line-height: 1.8;
  }

  .subBox {
    display: flex;
    justify-content: flex-start;
    gap: 12px;
    margin-left: 160px;
    padding-top: 24px;
    border-top: 1px solid #edf0ed;
  }
}

.flavorBox {
  width: 100%;

  .flavor-list {
    display: grid;
    gap: 12px;
    margin-bottom: 14px;
  }

  .flavor-row {
    display: grid;
    grid-template-columns: minmax(140px, 190px) minmax(0, 1fr) auto;
    align-items: start;
    gap: 12px;
    padding: 14px;
    border: 1px solid #e7ece8;
    border-radius: 8px;
    background: #f8faf8;
  }

  .flavor-name-input,
  .flavor-options,
  .flavor-option-input {
    width: 100%;
    max-width: none;
  }

  .flavor-options {
    display: flex;
    align-items: center;
    flex-wrap: wrap;
    gap: 8px;
    min-height: 40px;
    padding: 5px 8px;
    border: 1px solid #dfe4df;
    border-radius: 6px;
    background: #fff;
  }

  .flavor-option-input {
    flex: 1 1 180px;
    min-width: 150px;
  }

  .flavor-option-input :deep(.el-input__wrapper) {
    box-shadow: none;
  }

  .delete-flavor-button {
    min-height: 40px;
  }

  .flavor-hint {
    margin: 8px 0 0;
    color: #8a918c;
    font-size: 12px;
    line-height: 1.6;
  }
}

@media (max-width: 900px) {
  .addBrand-container {
    margin: 16px;

    .container {
      padding: 22px 16px;
    }
  }

  .dish-form {
    .form-grid {
      grid-template-columns: minmax(0, 1fr);
    }

    .subBox {
      margin-left: 0;
    }
  }

  .flavorBox .flavor-row {
    grid-template-columns: minmax(0, 1fr);
  }
}

@media (max-width: 600px) {
  .dish-form .image-form-item :deep(.upload-item) {
    grid-template-columns: minmax(0, 1fr);
    row-gap: 10px;
  }
}
</style>
