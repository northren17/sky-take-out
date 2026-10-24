<template>
  <div class="addBrand-container">
    <div class="container">
      <el-form ref="ruleForm"
               :model="ruleForm"
               :rules="rules"
               :inline="true"
               label-width="180px"
               class="demo-ruleForm"
      >
        <div>
          <el-form-item label="套餐名称:"
                        prop="name"
          >
            <el-input v-model="ruleForm.name"
                      placeholder="请填写套餐名称"
                      maxlength="14"
            />
          </el-form-item>
          <el-form-item label="套餐分类:"
                        prop="idType"
          >
            <el-select v-model="ruleForm.idType"
                       placeholder="请选择套餐分类"
                       @change="$forceUpdate()"
            >
              <el-option v-for="(item, index) in setMealList"
                         :key="index"
                         :label="item.name"
                         :value="item.id"
              />
            </el-select>
          </el-form-item>
        </div>
        <div>
          <el-form-item label="套餐价格:"
                        prop="price"
          >
            <el-input v-model="ruleForm.price"
                      placeholder="请设置套餐价格"
            />
          </el-form-item>
        </div>
        <div>
          <el-form-item label="套餐库存:"
                        prop="stock"
          >
            <el-input-number v-model="ruleForm.stock"
                             :min="-1"
                             :step="1"
                             controls-position="right"
            />
            <span class="stock-hint">-1 表示不限库存</span>
          </el-form-item>
        </div>
        <div>
          <el-form-item label="套餐菜品:"
                        required
          >
            <el-form-item>
              <div class="addDish">
                <span v-if="dishTable.length == 0"
                      class="addBut"
                      @click="openAddDish('new')"
                >
                  + 添加菜品</span>
                <div v-if="dishTable.length != 0"
                     class="content"
                >
                  <div class="addBut"
                       style="margin-bottom: 20px"
                       @click="openAddDish('change')"
                  >
                    + 添加菜品
                  </div>
                  <div class="table">
                    <el-table :data="dishTable"
                              style="width: 100%"
                    >
                      <el-table-column prop="name"
                                       label="名称"
                                       width="180"
                                       align="center"
                      />
                      <el-table-column prop="price"
                                       label="原价"
                                       width="180"
                                       align="center"
                      >
                        <template #default="scope">
                          {{ (Number(scope.row.price).toFixed(2) * 100) / 100 }}
                        </template>
                      </el-table-column>
                      <el-table-column prop="address"
                                       label="份数"
                                       width="180"
                                       align="center"
                      >
                        <template #default="scope">
                          <input v-model.number="scope.row.copies"
                                 type="number"
                                 min="1"
                                 max="99"
                                 step="1"
                                 class="dish-copies-input"
                                 :aria-label="`${scope.row.name}份数`"
                                 @change="normalizeDishCopies(scope.row)"
                          />
                        </template>
                      </el-table-column>
                      <el-table-column prop="address"
                                       label="操作"
                                       width="100"
                                       align="center"
                                       class-name="admin-table-action-column"
                      >
                        <template #default="scope">
                          <div class="admin-table-actions">
                            <el-button type="danger" plain size="small" @click="delDishHandle(scope.$index)">
                              删除
                            </el-button>
                          </div>
                        </template>
                      </el-table-column>
                    </el-table>
                  </div>
                </div>
              </div>
            </el-form-item>
          </el-form-item>
        </div>
        <el-form-item label="套餐图片:">
          <div class="setmeal-image-preview">
            <img v-if="firstDishImage" :src="firstDishImage" alt="套餐首个菜品图片">
            <div v-else class="setmeal-image-placeholder">选择菜品后自动使用首个菜品图片</div>
            <span>套餐图片自动使用套餐中的首个菜品图片</span>
          </div>
        </el-form-item>
        <!-- <el-form-item
          label="商品码:"
          prop="code"
        >
          <el-input
            v-model="ruleForm.code"
            placeholder="请输入商品码"
          />
        </el-form-item> -->
        <div class="address">
          <el-form-item label="套餐描述:">
            <el-input v-model="ruleForm.description"
                      type="textarea"
                      :rows="3"
                      maxlength="200"
                      placeholder="套餐描述，最长200字"
            />
          </el-form-item>
        </div>
        <div class="subBox address">
          <el-form-item>
            <el-button @click="() => $router.back()">
              取消
            </el-button>
            <el-button type="primary"
                       :class="{ continue: actionType === 'add' }"
                       @click="submitForm('ruleForm', false)"
            >
              保存
            </el-button>
            <el-button v-if="actionType == 'add'"
                       type="primary"
                       @click="submitForm('ruleForm', true)"
            >
              保存并继续添加
            </el-button>
          </el-form-item>
        </div>
      </el-form>
    </div>
    <el-dialog v-if="dialogVisible"
               title="添加菜品"
               class="addDishList"
               v-model="dialogVisible"
               width="60%"
               :before-close="handleClose"
    >
      <el-input v-model="value"
                class="seachDish"
                placeholder="请输入菜品名称进行搜索"
                style="width: 293px; height: 40px"
                size="small"
                clearable
      >
        <template #prefix>
          <el-icon class="el-input__icon" style="cursor: pointer" @click="seachHandle">
            <Search />
          </el-icon>
        </template>
      </el-input>
      <AddDish v-if="dialogVisible"
               ref="adddish"
               :check-list="checkList"
               :seach-key="seachKey"
               :dish-list="dishList"
               @checkList="getCheckList"
      />
      <template #footer>
        <span class="dialog-footer">
        <el-button @click="handleClose">取 消</el-button>
        <el-button type="primary"
                   @click="addTableList"
        >添 加</el-button>
        </span>
      </template>
    </el-dialog>
  </div>
</template>

<script lang="ts">
import { Component, Vue } from 'vue-facing-decorator'
import HeadLable from '@/components/HeadLable/index.vue'
import AddDish from './components/AddDish.vue'
import { querySetmealById, addSetmeal, editSetmeal } from '@/api/setMeal'
import { getCategoryList } from '@/api/dish'

@Component({
  name: 'addShop',
  components: {
    HeadLable,
    AddDish
  }
})
export default class AddShop extends Vue {
  private value: string = ''
  private setMealList: [] = []
  private seachKey: string = ''
  private dishList: [] = []
  private actionType: string = ''
  private dishTable: [] = []
  private dialogVisible: boolean = false
  private checkList: any[] = []
  private ruleForm = {
    name: '',
    categoryId: '',
    price: '',
    stock: 100,
    code: '',
    image: '',
    description: '',
    dishList: [],
    status: true,
    idType: ''
  }

  get firstDishImage() {
    return (this.dishTable[0] as any)?.image || ''
  }

  get rules() {
    return {
      name: {
        required: true,
        validator: (rule: any, value: string, callback: (error?: Error) => void) => {
          if (!value) {
            callback(new Error('请输入套餐名称'))
          } else {
            const reg = /^([A-Za-z0-9\u4e00-\u9fa5]){2,20}$/
            if (!reg.test(value)) {
              callback(new Error('套餐名称输入不符，请输入2-20个字符'))
            } else {
              callback()
            }
          }
        },
        trigger: 'blur'
      },
      idType: {
        required: true,
        message: '请选择套餐分类',
        trigger: 'change'
      },
      price: {
        required: true,
        // 'message': '请输入套餐价格',
        validator: (rules: any, value: string, callback: (error?: Error) => void) => {
          const reg = /^([1-9]\d{0,5}|0)(\.\d{1,2})?$/
          if (!reg.test(value) || Number(value) <= 0) {
            callback(
              new Error(
                '套餐价格格式有误，请输入大于零且最多保留两位小数的金额'
              )
            )
          } else {
            callback()
          }
        },
        trigger: 'blur'
      },
      code: { required: true, message: '请输入商品码', trigger: 'blur' }
    }
  }

  created() {
    this.getDishTypeList()
    this.actionType = this.$route.query.id ? 'edit' : 'add'
    if (this.actionType == 'edit') {
      this.init()
    }
  }

  private async init() {
    querySetmealById(this.$route.query.id).then(res => {
      if (res && res.data && res.data.code === 1) {
        this.ruleForm = res.data.data
        this.ruleForm.status = res.data.data.status == '1'
        ;(this.ruleForm as any).price = res.data.data.price
        this.checkList = res.data.data.setmealDishes
        this.dishTable = res.data.data.setmealDishes.reverse()
        this.ruleForm.idType = res.data.data.categoryId
      } else {
        this.$message.error(res.data.msg)
      }
    })
  }
  private seachHandle() {
    this.seachKey = this.value
  }
  // 获取套餐分类
  private getDishTypeList() {
    getCategoryList({ type: 2, page: 1, pageSize: 1000 }).then(res => {
      if (res && res.data && res.data.code === 1) {
        this.setMealList = res.data.data.map((obj: any) => ({
          ...obj,
          idType: obj.id
        }))
      } else {
        this.$message.error(res.data.msg)
      }
    })
  }

  // 通过套餐ID获取菜品列表分类
  // private getDishList (id:number) {
  //   getDishListType({id}).then(res => {
  //     if (res.data.code == 200) {
  //       const { data } = res.data
  //       this.dishList = data
  //     } else {
  //       this.$message.error(res.data.desc)
  //     }
  //   })
  // }

  // 删除套餐菜品
  delDishHandle(index: any) {
    this.dishTable.splice(index, 1)
    this.checkList = this.dishTable
    // this.checkList.splice(index, 1)
  }

  // 获取添加菜品数据 - 确定加菜倒序展示
  private getCheckList(value: any) {
    this.checkList = [...value].reverse().map((dish: any) => ({
      ...dish,
      copies: Number(dish.copies) || 1
    }))
  }

  // 添加菜品
  openAddDish(st: string) {
    this.seachKey = ''
    this.dialogVisible = true
  }
  // 取消添加菜品
  handleClose(done: any) {
    // this.$refs.adddish.close()
    this.dialogVisible = false
    this.checkList = JSON.parse(JSON.stringify(this.dishTable))
    // this.dialogVisible = false
  }

  // 保存添加菜品列表
  public addTableList() {
    const existingCopies = new Map(
      (this.dishTable as any[]).map(dish => [dish.dishId, dish.copies])
    )
    this.dishTable = JSON.parse(JSON.stringify(this.checkList)).map((dish: any) => ({
      ...dish,
      copies: Number(existingCopies.get(dish.dishId) ?? dish.copies) || 1
    })) as any
    this.dialogVisible = false
  }

  private normalizeDishCopies(dish: any) {
    const copies = Number(dish.copies)
    dish.copies = Number.isFinite(copies)
      ? Math.min(99, Math.max(1, Math.trunc(copies)))
      : 1
  }

  public submitForm(formName: any, st: any) {
    (this.$refs[formName] as any).validate((valid: any) => {
      if (valid) {
        if (this.dishTable.length === 0) {
          return this.$message.error('套餐下菜品不能为空')
        }
        let prams = { ...this.ruleForm } as any
        prams.image = this.firstDishImage
        prams.setmealDishes = this.dishTable.map((obj: any) => ({
          copies: Number(obj.copies) || 1,
          dishId: obj.dishId,
          name: obj.name,
          price: obj.price
        }))
        ;(prams as any).status =
          this.actionType === 'add' ? 0 : this.ruleForm.status ? 1 : 0
        prams.categoryId = this.ruleForm.idType
        // delete prams.dishList
        if (this.actionType == 'add') {
          delete prams.id
          addSetmeal(prams)
            .then(res => {
              if (res && res.data && res.data.code === 1) {
                this.$message.success('套餐添加成功！')
                if (!st) {
                  this.$router.push({ path: '/setmeal' })
                } else {
                  (this as any).$refs.ruleForm.resetFields()
                  this.dishList = []
                  this.dishTable = []
                  this.ruleForm = {
                    name: '',
                    categoryId: '',
                    price: '',
                    stock: 100,
                    code: '',
                    image: '',
                    description: '',
                    dishList: [],
                    status: true,
                    id: '',
                    idType: ''
                  } as any
                }
              } else {
                this.$message.error(res.data.msg)
              }
            })
            .catch(err => {
              this.$message.error('请求出错了：' + err.message)
            })
        } else {
          delete prams.updateTime
          editSetmeal(prams)
            .then(res => {
              if (res.data.code === 1) {
                this.$message.success('套餐修改成功！')
                this.$router.push({ path: '/setmeal' })
              } else {
                // this.$message.error(res.data.desc || res.data.message)
              }
            })
            .catch(err => {
              this.$message.error('请求出错了：' + err.message)
            })
        }
      } else {
        // console.log('error submit!!')
        return false
      }
    })
  }

}
</script>
<style>
.avatar-uploader .el-icon-plus:after {
  position: absolute;
  display: inline-block;
  content: ' ' !important;
  left: calc(50% - 20px);
  top: calc(50% - 40px);
  width: 40px;
  height: 40px;
  background: url('./../../assets/icons/icon_upload@2x.png') center center
    no-repeat;
  background-size: 20px;
}
</style>
<style lang="scss">
// .el-form-item__error {
//   top: 90%;
// }
.addBrand-container {
  .avatar-uploader .el-upload {
    border: 1px dashed #d9d9d9;
    border-radius: 6px;
    cursor: pointer;
    position: relative;
    overflow: hidden;
  }

  .avatar-uploader .el-upload:hover {
    border-color: var(--admin-primary);
  }

  .avatar-uploader-icon {
    font-size: 28px;
    color: #8c939d;
    width: 200px;
    height: 160px;
    line-height: 160px;
    text-align: center;
  }

  .avatar {
    width: 200px;
    height: 160px;
    display: block;
  }

  // .el-form--inline .el-form-item__content {
  //   width: 293px;
  // }

  .el-input {
    width: 293px;
  }

  .address {
    .el-form-item__content {
      width: 777px !important;
    }
  }
  .el-input__prefix {
    top: 2px;
  }

  .addDish {
    .el-input {
      width: 130px;
    }

    .el-input-number__increase {
      border-left: solid 1px #fbe396;
      background: #fffbf0;
    }

    .el-input-number__decrease {
      border-right: solid 1px #fbe396;
      background: #fffbf0;
    }

    input {
      border: 1px solid #fbe396;
    }

    .table {
      border: solid 1px #ebeef5;
      border-radius: 3px;

      th {
        padding: 5px 0;
      }

      td {
        padding: 7px 0;
      }
    }
  }

  .addDishList {
    .seachDish {
      position: absolute;
      top: 12px;
      right: 20px;
    }

    .el-dialog__footer {
      padding-top: 27px;
    }

    .el-dialog__body {
      padding: 0;
      border-bottom: solid 1px #efefef;
    }
    .seachDish {
      .el-input__inner {
        height: 40px;
        line-height: 40px;
      }
    }
  }
}
</style>
<style lang="scss" scoped>
.dish-copies-input {
  width: 88px;
  height: 32px;
  padding: 0 8px;
  border: 1px solid #d8dde3;
  border-radius: 4px;
  color: #333;
  text-align: center;
  box-sizing: border-box;

  &:focus {
    border-color: var(--admin-primary);
    outline: none;
  }
}

.setmeal-image-preview {
  display: flex;
  align-items: center;
  gap: 12px;
  color: #748078;
  font-size: 13px;

  img,
  .setmeal-image-placeholder {
    width: 96px;
    height: 96px;
    border: 1px solid #dfe5df;
    border-radius: 4px;
    object-fit: cover;
  }

  .setmeal-image-placeholder {
    display: flex;
    align-items: center;
    justify-content: center;
    padding: 8px;
    box-sizing: border-box;
    text-align: center;
  }
}

.addBrand {
  &-container {
    margin: 30px;

    .container {
      position: relative;
      z-index: 1;
      background: #fff;
      padding: 30px;
      border-radius: 4px;
      min-height: 500px;

      .subBox {
        padding-top: 30px;
        text-align: center;
        border-top: solid 1px $gray-5;
      }
      .el-input {
        width: 350px;
      }
      .addDish {
        width: 777px;

        .addBut {
          background: var(--admin-primary);
          display: inline-block;
          padding: 0px 20px;
          border-radius: 3px;
          line-height: 40px;
          cursor: pointer;
          border-radius: 4px;
          color: #333333;
          font-weight: 500;
        }

        .content {
          background: #fafafb;
          padding: 20px;
          border: solid 1px #d8dde3;
          border-radius: 3px;
        }
      }
    }
  }
}
</style>
