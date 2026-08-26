#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
课外辅导班教学辅助管理程序
功能：班级管理、学生名单、上课记录、考勤统计、课时费管理等
"""

import json
import os
import csv
from datetime import datetime, date
from collections import defaultdict


# ==================== 常量定义 ====================
DATA_FILE = "teaching_data.json"
STUDENTS_FILE = "students.json"
CONFIG_FILE = "config.json"

DEFAULT_CLASSES = {
    "Python一级1班": "Python一级",
    "Python一级2班": "Python一级",
    "Python一级3班": "Python一级",
    "图形化班": "图形化"
}

COURSE_TYPES = ["正常课", "补课", "试听", "停课"]

DEFAULT_CONFIG = {
    "price_per_session": {
        "Python一级": 80,
        "图形化": 100
    },
    "semesters": []
}


def normalize_absent(absent_field):
    """将缺勤字段标准化为对象列表格式 [{name, madeUp, makeupRecordId, absentRemark}, ...]"""
    if not absent_field:
        return []
    if not isinstance(absent_field, list):
        return []
    result = []
    for item in absent_field:
        if isinstance(item, str):
            result.append({
                "name": item,
                "madeUp": False,
                "makeupRecordId": None,
                "absentRemark": ""
            })
        elif isinstance(item, dict):
            result.append({
                "name": item.get("name", ""),
                "madeUp": bool(item.get("madeUp", False)),
                "makeupRecordId": item.get("makeupRecordId"),
                "absentRemark": item.get("absentRemark", "")
            })
    return [x for x in result if x["name"]]


def get_absent_names(absent_field):
    """从缺勤字段中提取学生姓名列表"""
    return [a["name"] for a in normalize_absent(absent_field)]


def migrate_all_absent(records):
    """迁移所有记录的缺勤字段到新格式"""
    for r in records:
        r["absent"] = normalize_absent(r.get("absent", []))
    return records


def get_all_absent_records(records):
    """将所有记录展开为按学生逐条的缺勤明细"""
    result = []
    for record in records:
        absent_list = normalize_absent(record.get("absent", []))
        for idx, absent_item in enumerate(absent_list):
            result.append({
                "recordId": record.get("id"),
                "absentIndex": idx,
                "date": record.get("date"),
                "class": record.get("class", record.get("className", "")),
                "course": record.get("course"),
                "sessions": record.get("sessions"),
                "type": record.get("type"),
                "semester": record.get("semester", ""),
                "recordRemark": record.get("remark", ""),
                "studentName": absent_item["name"],
                "madeUp": absent_item["madeUp"],
                "makeupRecordId": absent_item["makeupRecordId"],
                "absentRemark": absent_item.get("absentRemark", "")
            })
    return result


# ==================== 数据管理模块 ====================
def load_json(filepath, default=None):
    """加载JSON文件，如果不存在则返回默认值"""
    if default is None:
        default = {}
    try:
        if os.path.exists(filepath):
            with open(filepath, 'r', encoding='utf-8') as f:
                return json.load(f)
        else:
            save_json(filepath, default)
            return default
    except Exception as e:
        print(f"加载文件 {filepath} 出错: {e}")
        return default


def save_json(filepath, data):
    """保存数据到JSON文件"""
    try:
        with open(filepath, 'w', encoding='utf-8') as f:
            json.dump(data, f, ensure_ascii=False, indent=2)
        return True
    except Exception as e:
        print(f"保存文件 {filepath} 出错: {e}")
        return False


# 全局数据缓存
students_data = {}
teaching_data = {"records": []}
config_data = {}


def init_data():
    """初始化加载所有数据"""
    global students_data, teaching_data, config_data

    # 加载学生名单
    students_data = load_json(STUDENTS_FILE, {})
    # 如果学生名单为空，初始化默认班级空名单
    if not students_data:
        for class_name in DEFAULT_CLASSES:
            students_data[class_name] = []
        save_json(STUDENTS_FILE, students_data)

    # 加载上课记录
    teaching_data = load_json(DATA_FILE, {"records": []})
    # 迁移缺勤字段格式
    teaching_data["records"] = migrate_all_absent(teaching_data.get("records", []))
    # 迁移班级字段：双写 class + className，保证前后端兼容
    for r in teaching_data.get("records", []):
        if not r.get("className") and r.get("class"):
            r["className"] = r["class"]
        if not r.get("class") and r.get("className"):
            r["class"] = r["className"]
    save_json(DATA_FILE, teaching_data)

    # 加载配置
    config_data = load_json(CONFIG_FILE, DEFAULT_CONFIG.copy())
    # 确保配置完整性
    if "price_per_session" not in config_data:
        config_data["price_per_session"] = DEFAULT_CONFIG["price_per_session"]
    if "semesters" not in config_data:
        config_data["semesters"] = []
    # 确保每个学期都有 classes 和 students 字段，并规范化classes为对象格式
    for sem in config_data["semesters"]:
        if "classes" not in sem or not isinstance(sem["classes"], list):
            sem["classes"] = []
        # 迁移字符串格式班级名 -> {name, course} 对象格式
        normalized_classes = []
        for cls in sem["classes"]:
            if isinstance(cls, str):
                normalized_classes.append({"name": cls, "course": DEFAULT_CLASSES.get(cls, "未知课程")})
            elif isinstance(cls, dict):
                normalized_classes.append({
                    "name": cls.get("name", ""),
                    "course": cls.get("course", DEFAULT_CLASSES.get(cls.get("name",""), "未知课程"))
                })
        sem["classes"] = [c for c in normalized_classes if c["name"]]
        if "students" not in sem or not isinstance(sem["students"], dict):
            sem["students"] = {}
    save_json(CONFIG_FILE, config_data)


def sem_class_names(sem):
    """获取学期的班级名称列表"""
    return [c["name"] for c in sem.get("classes", []) if isinstance(c, dict)]


def sem_find_class(sem, class_name):
    """在学期中查找班级对象"""
    for c in sem.get("classes", []):
        if isinstance(c, dict) and c.get("name") == class_name:
            return c
    return None


def save_all():
    """保存所有数据"""
    save_json(STUDENTS_FILE, students_data)
    save_json(DATA_FILE, teaching_data)
    save_json(CONFIG_FILE, config_data)


# ==================== 输入验证工具函数 ====================
def input_date(prompt="请输入日期 (YYYY-MM-DD)，直接回车使用今天: "):
    """输入并验证日期"""
    while True:
        date_str = input(prompt).strip()
        if not date_str:
            return date.today().strftime("%Y-%m-%d")
        try:
            datetime.strptime(date_str, "%Y-%m-%d")
            return date_str
        except ValueError:
            print("日期格式错误，请使用 YYYY-MM-DD 格式")


def input_positive_int(prompt, default=1):
    """输入正整数"""
    while True:
        value = input(prompt).strip()
        if not value:
            return default
        try:
            num = int(value)
            if num > 0:
                return num
            else:
                print("请输入正整数")
        except ValueError:
            print("请输入有效的正整数")


def input_month(prompt="请输入月份 (YYYY-MM): "):
    """输入并验证月份"""
    while True:
        month_str = input(prompt).strip()
        try:
            datetime.strptime(month_str, "%Y-%m")
            return month_str
        except ValueError:
            print("月份格式错误，请使用 YYYY-MM 格式")


def select_from_list(options, title="请选择"):
    """从列表中选择一个选项，返回索引(从0开始)"""
    print(f"\n{title}:")
    for i, option in enumerate(options, 1):
        print(f"  {i}. {option}")

    while True:
        choice = input(f"请输入编号 (1-{len(options)}): ").strip()
        try:
            idx = int(choice) - 1
            if 0 <= idx < len(options):
                return idx
            else:
                print(f"请输入 1-{len(options)} 之间的数字")
        except ValueError:
            print("请输入有效数字")


def select_multiple_from_list(options, title="请选择（可多选，用逗号分隔）"):
    """从列表中多选，返回选中的索引列表"""
    print(f"\n{title}:")
    for i, option in enumerate(options, 1):
        print(f"  {i}. {option}")

    while True:
        choice = input("请输入编号（如 1,3,5），直接回车表示无: ").strip()
        if not choice:
            return []

        try:
            indices = [int(x.strip()) - 1 for x in choice.split(',')]
            valid_indices = [i for i in indices if 0 <= i < len(options)]
            if valid_indices:
                return valid_indices
            else:
                print("没有有效的选择")
        except ValueError:
            print("输入格式错误，请用逗号分隔数字")


def clear_screen():
    """清屏"""
    os.system('cls' if os.name == 'nt' else 'clear')


def wait_return(message="按回车返回..."):
    """等待用户按回车"""
    input(f"\n{message}")


# ==================== 学生名单管理 ====================
def manage_students():
    """学生名单管理主菜单"""
    while True:
        clear_screen()
        print("=" * 50)
        print("学生名单管理")
        print("=" * 50)

        classes = list(students_data.keys())
        for i, class_name in enumerate(classes, 1):
            count = len(students_data[class_name])
            print(f"  {i}. {class_name} ({count}人)")

        print("\n  0. 返回主菜单")

        choice = input("\n请选择班级进行操作: ").strip()
        if choice == '0':
            return

        try:
            idx = int(choice) - 1
            if 0 <= idx < len(classes):
                manage_class_students(classes[idx])
            else:
                print("无效选择")
                wait_return()
        except ValueError:
            print("请输入有效数字")
            wait_return()


def manage_class_students(class_name):
    """管理某个班级的学生名单"""
    while True:
        clear_screen()
        print("=" * 50)
        print(f"班级: {class_name}")
        print("=" * 50)

        students = students_data.get(class_name, [])
        if students:
            print("\n当前学生名单:")
            for i, name in enumerate(students, 1):
                print(f"  {i}. {name}")
        else:
            print("\n暂无学生")

        print("\n  1. 添加学生")
        print("  2. 删除学生")
        print("  3. 修改学生姓名")
        print("  4. 学生转班 (转到其他班级)")
        print("  0. 返回")

        choice = input("\n请选择操作: ").strip()

        if choice == '1':
            name = input("请输入学生姓名: ").strip()
            if name:
                if name in students:
                    print(f"学生 {name} 已存在")
                else:
                    students.append(name)
                    students_data[class_name] = students
                    save_all()
                    print(f"已添加学生: {name}")
            wait_return()

        elif choice == '2':
            if not students:
                print("暂无学生可删除")
                wait_return()
                continue

            idx = select_from_list(students, "请选择要删除的学生")
            name = students.pop(idx)
            students_data[class_name] = students
            save_all()
            print(f"已删除学生: {name}")
            wait_return()

        elif choice == '3':
            if not students:
                print("暂无学生可修改")
                wait_return()
                continue

            idx = select_from_list(students, "请选择要修改的学生")
            new_name = input(f"请输入新姓名 (原: {students[idx]}): ").strip()
            if new_name:
                old_name = students[idx]
                students[idx] = new_name
                students_data[class_name] = students
                save_all()
                print(f"已将 {old_name} 修改为 {new_name}")
            wait_return()

        elif choice == '4':
            if not students:
                print("暂无学生可转班")
                wait_return()
                continue

            all_classes = list(students_data.keys())
            if len(all_classes) < 2:
                print("可用班级不足 2 个，无法转班")
                wait_return()
                continue

            stu_idx = select_from_list(students, "请选择要转班的学生")
            student_name = students[stu_idx]

            print("\n可选的目标班级:")
            target_options = [c for c in all_classes if c != class_name]
            for i, c in enumerate(target_options, 1):
                n = len(students_data.get(c, []))
                print(f"  {i}. {c} ({n}人)")

            tc = input(f"\n请选择目标班级 (1-{len(target_options)}): ").strip()
            try:
                ti = int(tc) - 1
                if not (0 <= ti < len(target_options)):
                    print("无效选择")
                    wait_return()
                    continue
                target_class = target_options[ti]
                target_students = students_data.get(target_class, [])
                if student_name in target_students:
                    print(f"目标班级「{target_class}」已存在同名学生「{student_name}」，转班取消。")
                    wait_return()
                    continue

                # 执行转班
                students.pop(stu_idx)
                students_data[class_name] = students
                target_students.append(student_name)
                students_data[target_class] = target_students
                save_all()
                print(f"✅ 转班成功：「{student_name}」已从 {class_name}（{len(students)}人）转入 {target_class}（{len(target_students)}人）。")
            except ValueError:
                print("请输入有效数字")
            wait_return()

        elif choice == '0':
            return
        else:
            print("无效选择")
            wait_return()


# ==================== 上课记录管理 ====================
def add_record():
    """添加上课记录"""
    clear_screen()
    print("=" * 50)
    print("添加上课记录")
    print("=" * 50)

    # 输入日期
    record_date = input_date()

    # 选择班级
    classes = list(students_data.keys())
    class_idx = select_from_list(classes, "请选择班级")
    class_name = classes[class_idx]
    course_name = DEFAULT_CLASSES.get(class_name, "未知课程")

    # 输入节数
    sessions = input_positive_int(f"请输入上课节数 (默认1): ", 1)

    # 选择课程类型
    type_idx = select_from_list(COURSE_TYPES, "请选择课程类型")
    record_type = COURSE_TYPES[type_idx]

    # 选择缺勤学生
    students = students_data.get(class_name, [])
    absent_names = []

    if students:
        absent_indices = select_multiple_from_list(
            students,
            "请选择缺勤学生（可多选）"
        )
        absent_names = [students[i] for i in absent_indices]

        # 询问是否手动添加不在名单中的学生
        manual = input("是否手动添加其他缺勤学生姓名？(y/n，直接回车跳过): ").strip().lower()
        if manual == 'y':
            while True:
                name = input("请输入学生姓名 (直接回车结束): ").strip()
                if not name:
                    break
                if name not in students:
                    add_to_list = input(
                        f"{name} 不在班级名单中，是否加入该班名单？(y/n): "
                    ).strip().lower()
                    if add_to_list == 'y':
                        students.append(name)
                        students_data[class_name] = students
                        save_all()
                        print(f"已将 {name} 加入班级名单")
                absent_names.append(name)
    else:
        print("该班级暂无学生名单，将跳过缺勤选择")
        manual_absent = input("请输入缺勤学生姓名 (多个用逗号分隔，直接回车跳过): ").strip()
        if manual_absent:
            absent_names = [name.strip() for name in manual_absent.split(',') if name.strip()]

    # 输入备注
    remark = input("备注 (直接回车跳过): ").strip()

    # 创建记录（使用新的缺勤对象格式）+ 双写 class/className 确保前后端兼容
    absent_list = normalize_absent(absent_names)
    record = {
        "id": len(teaching_data["records"]) + 1,
        "date": record_date,
        "class": class_name,
        "className": class_name,
        "course": course_name,
        "sessions": sessions,
        "type": record_type,
        "absent": absent_list,
        "remark": remark
    }

    teaching_data["records"].append(record)
    save_all()

    print("\n✓ 记录添加成功！")
    print(f"  日期: {record_date}")
    print(f"  班级: {class_name}")
    print(f"  课程: {course_name}")
    print(f"  节数: {sessions}")
    print(f"  类型: {record_type}")
    print(f"  缺勤: {', '.join(absent_names) if absent_names else '无'}")
    if remark:
        print(f"  备注: {remark}")

    wait_return()


def absent_overview():
    """学生缺勤总览 - 显示待补课/已补课明细"""
    clear_screen()
    print("=" * 60)
    print("学生缺勤总览（含补课状态）")
    print("=" * 60)

    records = teaching_data["records"]
    all_absent = get_all_absent_records(records)

    # 按学生分组
    student_map = defaultdict(list)
    for a in all_absent:
        student_map[a["studentName"]].append(a)

    if not student_map:
        print("\n暂无缺勤记录")
        wait_return()
        return

    print(f"\n共涉及 {len(student_map)} 名学生，{len(all_absent)} 条缺勤记录")
    pending = sum(1 for a in all_absent if not a["madeUp"])
    done = sum(1 for a in all_absent if a["madeUp"])
    print(f"待补课: {pending} 条，已补课: {done} 条")

    print("\n" + "=" * 60)
    print("按学生查看缺勤明细:")
    students = sorted(student_map.keys())
    for i, name in enumerate(students, 1):
        lst = student_map[name]
        p = sum(1 for x in lst if not x["madeUp"])
        d = len(lst) - p
        print(f"  {i}. {name}  (共{len(lst)}条 待补{p} 已补{d})")

    choice = input("\n请选择学生查看详细（直接回车返回）: ").strip()
    if not choice:
        return

    try:
        idx = int(choice) - 1
        if 0 <= idx < len(students):
            name = students[idx]
            lst = sorted(student_map[name], key=lambda x: x["date"])

            print(f"\n{'=' * 60}")
            print(f"学生: {name}  缺勤明细")
            print(f"{'=' * 60}")
            print(f"{'日期':<12}{'班级':<16}{'课程':<12}{'节数':<6}{'状态':<8}{'备注'}")
            print("-" * 70)

            for item in lst:
                status = "已补课" if item["madeUp"] else "待补课"
                remarks = []
                if item["recordRemark"]:
                    remarks.append(f"课程:{item['recordRemark']}")
                if item["absentRemark"]:
                    remarks.append(f"缺勤:{item['absentRemark']}")
                remark_str = " | ".join(remarks) if remarks else "-"
                print(f"{item['date']:<12}{item['class']:<16}{item['course']:<12}"
                      f"{item['sessions']:<6}{status:<8}{remark_str}")

                if item["madeUp"] and item["makeupRecordId"]:
                    mk = next((r for r in records if r.get("id") == item["makeupRecordId"]), None)
                    if mk:
                        mk_names = get_absent_names(mk.get("absent"))
                        print(f"         → 补课: {mk.get('date')} {mk.get('type')} "
                              f"{mk.get('sessions')}节 {mk.get('remark','')[:30]}")

            wait_return()
    except ValueError:
        print("输入无效")
        wait_return()


def export_absent_details():
    """导出缺勤明细CSV（每条缺勤一行）"""
    clear_screen()
    print("=" * 50)
    print("导出缺勤明细（含班级、课程、备注、补课状态）")
    print("=" * 50)

    records = teaching_data["records"]
    all_absent = get_all_absent_records(records)
    if not all_absent:
        print("\n暂无缺勤记录可导出")
        wait_return()
        return

    filename = f"absent_details_{datetime.now().strftime('%Y%m%d_%H%M%S')}.csv"
    try:
        with open(filename, 'w', newline='', encoding='utf-8-sig') as f:
            writer = csv.writer(f)
            writer.writerow(["缺课日期", "缺课班级", "课程内容", "节数",
                             "学生姓名", "补课状态", "课程备注", "学生缺勤备注",
                             "补课日期", "补课节数", "补课备注"])
            for a in sorted(all_absent, key=lambda x: x["date"]):
                mk_date, mk_sessions, mk_remark = "", "", ""
                if a["madeUp"] and a["makeupRecordId"]:
                    mk = next((r for r in records if r.get("id") == a["makeupRecordId"]), None)
                    if mk:
                        mk_date = mk.get("date", "")
                        mk_sessions = mk.get("sessions", "")
                        mk_remark = mk.get("remark", "")
                writer.writerow([
                    a["date"], a["class"], a["course"], a["sessions"],
                    a["studentName"], "已补课" if a["madeUp"] else "待补课",
                    a["recordRemark"], a["absentRemark"],
                    mk_date, mk_sessions, mk_remark
                ])
        print(f"\n✓ 已导出 {len(all_absent)} 条缺勤明细到: {filename}")
    except Exception as e:
        print(f"\n导出失败: {e}")

    wait_return()


def view_all_records():
    """查看所有上课记录"""
    clear_screen()
    print("=" * 50)
    print("所有上课记录")
    print("=" * 50)

    records = teaching_data["records"]
    if not records:
        print("\n暂无记录")
        wait_return()
        return

    # 按日期倒序显示
    sorted_records = sorted(records, key=lambda x: x["date"], reverse=True)

    print(f"\n{'序号':<6}{'日期':<12}{'班级':<15}{'课程':<12}{'节数':<6}{'类型':<8}{'缺勤':<20}{'备注'}")
    print("-" * 90)

    for i, record in enumerate(sorted_records, 1):
        absent_names = get_absent_names(record.get("absent", []))
        absent_str = ', '.join(absent_names) if absent_names else "无"
        remark = record.get("remark", "")
        print(f"{i:<6}{record['date']:<12}{record['class']:<15}{record['course']:<12}"
              f"{record['sessions']:<6}{record['type']:<8}{absent_str:<20}{remark}")

    wait_return()


def modify_record():
    """修改上课记录"""
    clear_screen()
    print("=" * 50)
    print("修改上课记录")
    print("=" * 50)

    records = teaching_data["records"]
    if not records:
        print("\n暂无记录")
        wait_return()
        return

    # 显示记录列表
    sorted_indices = sorted(range(len(records)), key=lambda x: records[x]["date"], reverse=True)

    print(f"\n{'序号':<6}{'日期':<12}{'班级':<15}{'课程':<12}{'节数':<6}{'类型':<8}{'缺勤'}")
    print("-" * 70)

    for display_idx, record_idx in enumerate(sorted_indices, 1):
        record = records[record_idx]
        absent_names = get_absent_names(record.get("absent", []))
        absent_str = ', '.join(absent_names) if absent_names else "无"
        print(f"{display_idx:<6}{record['date']:<12}{record['class']:<15}{record['course']:<12}"
              f"{record['sessions']:<6}{record['type']:<8}{absent_str}")

    # 选择要修改的记录
    choice = input(f"\n请选择要修改的序号 (1-{len(records)}), 0返回: ").strip()
    if choice == '0':
        return

    try:
        display_idx = int(choice) - 1
        if 0 <= display_idx < len(records):
            record_idx = sorted_indices[display_idx]
            record = records[record_idx]

            print(f"\n正在修改记录:")
            print(f"  原日期: {record['date']}")
            print(f"  原节数: {record['sessions']}")
            print(f"  原类型: {record['type']}")
            absent_names = get_absent_names(record.get("absent", []))
            print(f"  原缺勤: {', '.join(absent_names) if absent_names else '无'}")
            print(f"  原备注: {record.get('remark', '')}")

            # 修改日期
            new_date = input(f"新日期 (直接回车保留: {record['date']}): ").strip()
            if new_date:
                try:
                    datetime.strptime(new_date, "%Y-%m-%d")
                    record["date"] = new_date
                except ValueError:
                    print("日期格式错误，保留原值")

            # 修改节数
            new_sessions = input(f"新节数 (直接回车保留: {record['sessions']}): ").strip()
            if new_sessions:
                try:
                    sessions = int(new_sessions)
                    if sessions > 0:
                        record["sessions"] = sessions
                    else:
                        print("节数必须为正整数，保留原值")
                except ValueError:
                    print("输入无效，保留原值")

            # 修改课程类型
            print("\n课程类型:")
            for i, t in enumerate(COURSE_TYPES, 1):
                marker = " ←当前" if t == record["type"] else ""
                print(f"  {i}. {t}{marker}")
            type_choice = input(f"新类型编号 (直接回车保留: {record['type']}): ").strip()
            if type_choice:
                try:
                    type_idx = int(type_choice) - 1
                    if 0 <= type_idx < len(COURSE_TYPES):
                        record["type"] = COURSE_TYPES[type_idx]
                except ValueError:
                    pass

            # 修改缺勤学生
            class_name = record["class"]
            students = students_data.get(class_name, [])
            old_absent = normalize_absent(record.get("absent", []))
            old_absent_names = {a["name"] for a in old_absent}
            if students:
                print(f"\n班级学生名单:")
                for i, name in enumerate(students, 1):
                    in_absent = " ✓" if name in old_absent_names else ""
                    print(f"  {i}. {name}{in_absent}")

                absent_input = input("\n重新选择缺勤学生编号 (用逗号分隔，直接回车保留原值): ").strip()
                if absent_input:
                    try:
                        indices = [int(x.strip()) - 1 for x in absent_input.split(',')]
                        new_absent_names = [students[i] for i in indices if 0 <= i < len(students)]

                        # 询问是否添加手动输入的姓名
                        manual = input("是否添加其他缺勤学生？(y/n): ").strip().lower()
                        if manual == 'y':
                            while True:
                                name = input("姓名 (直接回车结束): ").strip()
                                if not name:
                                    break
                                if name not in students:
                                    add = input(f"将 {name} 加入班级名单？(y/n): ").strip().lower()
                                    if add == 'y':
                                        students.append(name)
                                        students_data[class_name] = students
                                if name not in new_absent_names:
                                    new_absent_names.append(name)

                        # 构造新的absent列表，保留原有的补课状态和备注
                        new_absent = []
                        for name in new_absent_names:
                            old = next((a for a in old_absent if a["name"] == name), None)
                            if old:
                                new_absent.append(dict(old))
                            else:
                                new_absent.append({
                                    "name": name,
                                    "madeUp": False,
                                    "makeupRecordId": None,
                                    "absentRemark": ""
                                })
                        record["absent"] = new_absent
                    except ValueError:
                        print("输入格式错误，保留原值")

            # 修改备注
            new_remark = input(f"新备注 (直接回车保留): ").strip()
            if new_remark or new_remark == "":
                if new_remark != "":
                    record["remark"] = new_remark

            # 保存
            save_all()
            print("\n✓ 记录修改成功！")
            wait_return()
        else:
            print("无效序号")
            wait_return()
    except ValueError:
        print("输入无效")
        wait_return()


def delete_record():
    """删除上课记录"""
    clear_screen()
    print("=" * 50)
    print("删除上课记录")
    print("=" * 50)

    records = teaching_data["records"]
    if not records:
        print("\n暂无记录")
        wait_return()
        return

    # 显示记录列表
    sorted_indices = sorted(range(len(records)), key=lambda x: records[x]["date"], reverse=True)

    print(f"\n{'序号':<6}{'日期':<12}{'班级':<15}{'课程':<12}{'节数':<6}{'类型':<8}{'缺勤'}")
    print("-" * 70)

    for display_idx, record_idx in enumerate(sorted_indices, 1):
        record = records[record_idx]
        absent_names = get_absent_names(record.get("absent", []))
        absent_str = ', '.join(absent_names) if absent_names else "无"
        print(f"{display_idx:<6}{record['date']:<12}{record['class']:<15}{record['course']:<12}"
              f"{record['sessions']:<6}{record['type']:<8}{absent_str}")

    choice = input(f"\n请选择要删除的序号 (1-{len(records)}), 0返回: ").strip()
    if choice == '0':
        return

    try:
        display_idx = int(choice) - 1
        if 0 <= display_idx < len(records):
            record_idx = sorted_indices[display_idx]
            record = records[record_idx]

            confirm = input(
                f"确认删除 {record['date']} {record['class']} 的记录？(y/n): "
            ).strip().lower()

            if confirm == 'y':
                records.pop(record_idx)
                # 重新编号
                for i, rec in enumerate(records, 1):
                    rec["id"] = i
                save_all()
                print("✓ 记录已删除")
            else:
                print("已取消删除")

            wait_return()
        else:
            print("无效序号")
            wait_return()
    except ValueError:
        print("输入无效")
        wait_return()


def view_records_by_class():
    """按班级查看记录"""
    clear_screen()
    print("=" * 50)
    print("按班级查看记录")
    print("=" * 50)

    classes = list(students_data.keys())
    class_idx = select_from_list(classes, "请选择班级")
    class_name = classes[class_idx]

    records = teaching_data["records"]
    class_records = [r for r in records if r["class"] == class_name]

    if not class_records:
        print(f"\n{class_name} 暂无记录")
        wait_return()
        return

    # 按日期倒序
    class_records.sort(key=lambda x: x["date"], reverse=True)

    print(f"\n班级: {class_name}")
    print(f"{'序号':<6}{'日期':<12}{'课程':<12}{'节数':<6}{'类型':<8}{'缺勤':<20}{'备注'}")
    print("-" * 75)

    for i, record in enumerate(class_records, 1):
        absent_names = get_absent_names(record.get("absent", []))
        absent_str = ', '.join(absent_names) if absent_names else "无"
        remark = record.get("remark", "")
        print(f"{i:<6}{record['date']:<12}{record['course']:<12}{record['sessions']:<6}"
              f"{record['type']:<8}{absent_str:<20}{remark}")

    wait_return()


# ==================== 统计功能 ====================
def monthly_statistics():
    """按月统计课时"""
    clear_screen()
    print("=" * 50)
    print("月度课时统计")
    print("=" * 50)

    month = input_month()

    # 询问统计范围
    print("\n统计范围:")
    print("  1. 仅正常课")
    print("  2. 正常课 + 补课")
    print("  3. 正常课 + 补课 + 试听")
    scope_choice = input("请选择 (1-3，默认2): ").strip()

    if scope_choice == '1':
        included_types = ["正常课"]
    elif scope_choice == '3':
        included_types = ["正常课", "补课", "试听"]
    else:
        included_types = ["正常课", "补课"]

    records = teaching_data["records"]

    # 筛选当月记录
    month_records = [r for r in records if r["date"].startswith(month)]

    if not month_records:
        print(f"\n{month} 暂无记录")
        wait_return()
        return

    # 总体统计
    total_sessions = 0
    total_days = set()
    class_stats = defaultdict(lambda: {"sessions": 0, "days": set()})

    for record in month_records:
        if record["type"] in included_types:
            total_sessions += record["sessions"]
            total_days.add(record["date"])
            class_stats[record["class"]]["sessions"] += record["sessions"]
            class_stats[record["class"]]["days"].add(record["date"])

    print(f"\n{'='*50}")
    print(f"月份: {month}")
    print(f"统计范围: {', '.join(included_types)}")
    print(f"{'='*50}")
    print(f"\n总体统计:")
    print(f"  总上课节数: {total_sessions}")
    print(f"  总上课天数: {len(total_days)}")

    print(f"\n按班级统计:")
    print(f"  {'班级':<15}{'节数':<10}{'天数'}")
    print(f"  {'-'*35}")
    for class_name, stats in sorted(class_stats.items()):
        print(f"  {class_name:<15}{stats['sessions']:<10}{len(stats['days'])}")

    wait_return()


def student_absent_overview():
    """学生缺勤总览（新：按学生姓名查询，含补课状态）"""
    clear_screen()
    print("=" * 50)
    print("学生缺勤总览（按姓名查询）")
    print("=" * 50)

    student_name = input("请输入学生姓名: ").strip()
    if not student_name:
        print("姓名不能为空")
        wait_return()
        return

    records = teaching_data["records"]
    all_absent = get_all_absent_records(records)
    stu_records = [a for a in all_absent if a["studentName"] == student_name]

    if not stu_records:
        print(f"\n{student_name} 暂无缺勤记录")
        wait_return()
        return

    stu_records.sort(key=lambda x: x["date"])

    pending = sum(1 for a in stu_records if not a["madeUp"])
    done = len(stu_records) - pending
    print(f"\n学生: {student_name}")
    print(f"总缺勤次数: {len(stu_records)} (待补{pending}, 已补{done})")
    print(f"\n{'日期':<12}{'班级':<15}{'课程':<12}{'节数':<6}{'状态':<8}{'备注'}")
    print("-" * 75)

    for item in stu_records:
        status = "已补课" if item["madeUp"] else "待补课"
        remarks = []
        if item["recordRemark"]:
            remarks.append(item["recordRemark"])
        if item["absentRemark"]:
            remarks.append(f"[{item['absentRemark']}]")
        remark_str = " ".join(remarks) if remarks else "-"
        print(f"{item['date']:<12}{item['class']:<15}{item['course']:<12}"
              f"{item['sessions']:<6}{status:<8}{remark_str}")
        if item["madeUp"] and item["makeupRecordId"]:
            mk = next((r for r in records if r.get("id") == item["makeupRecordId"]), None)
            if mk:
                print(f"         → 补课记录: {mk.get('date')} {mk.get('sessions')}节 {mk.get('remark','')[:30]}")

    # 按月统计缺勤（待补课）
    monthly_absent = defaultdict(int)
    for a in stu_records:
        if not a["madeUp"]:
            monthly_absent[a["date"][:7]] += a["sessions"]

    if monthly_absent:
        print(f"\n按月待补课节数统计:")
        for month in sorted(monthly_absent.keys()):
            print(f"  {month}: {monthly_absent[month]} 节")

    wait_return()


def attendance_rate_statistics():
    """出勤率统计"""
    clear_screen()
    print("=" * 50)
    print("出勤率统计")
    print("=" * 50)

    # 选择班级
    classes = list(students_data.keys())
    class_idx = select_from_list(classes, "请选择班级")
    class_name = classes[class_idx]

    # 输入时间范围
    month = input_month("请输入月份 (YYYY-MM)，直接回车统计全部: ")

    records = teaching_data["records"]

    # 筛选记录
    if month:
        class_records = [
            r for r in records
            if r["class"] == class_name and r["date"].startswith(month)
        ]
    else:
        class_records = [r for r in records if r["class"] == class_name]

    if not class_records:
        print(f"\n该时间段暂无记录")
        wait_return()
        return

    # 获取班级学生名单
    students = students_data.get(class_name, [])
    if not students:
        print("该班级暂无学生名单")
        wait_return()
        return

    # 计算每个学生的应到节数和缺勤节数（待补课）
    total_sessions = sum(r["sessions"] for r in class_records)

    print(f"\n班级: {class_name}")
    if month:
        print(f"月份: {month}")
    print(f"总上课节数: {total_sessions}")

    print(f"\n{'学生姓名':<12}{'应到节数':<10}{'待补节数':<10}{'已补节数':<10}{'出勤率'}")
    print("-" * 55)

    for student in students:
        pending_sessions = 0
        made_up_sessions = 0
        for record in class_records:
            absent_list = normalize_absent(record.get("absent", []))
            for a in absent_list:
                if a["name"] == student:
                    if a["madeUp"]:
                        made_up_sessions += record["sessions"]
                    else:
                        pending_sessions += record["sessions"]

        absent_sessions = pending_sessions  # 出勤率只算待补课的
        present_sessions = total_sessions - absent_sessions
        if total_sessions > 0:
            rate = (present_sessions / total_sessions) * 100
        else:
            rate = 0

        print(f"{student:<12}{total_sessions:<10}{pending_sessions:<10}{made_up_sessions:<10}{rate:.1f}%")

    wait_return()


# ==================== 课时费管理 ====================
def manage_price():
    """管理课时费单价"""
    while True:
        clear_screen()
        print("=" * 50)
        print("课时费单价管理")
        print("=" * 50)

        prices = config_data.get("price_per_session", {})

        print("\n当前单价设置:")
        for course, price in prices.items():
            print(f"  {course}: {price} 元/节")

        print("\n  1. 修改单价")
        print("  2. 添加新课程单价")
        print("  0. 返回")

        choice = input("\n请选择操作: ").strip()

        if choice == '1':
            courses = list(prices.keys())
            if not courses:
                print("暂无课程")
                wait_return()
                continue

            idx = select_from_list(courses, "请选择课程")
            course = courses[idx]
            new_price = input(f"新单价 (当前: {prices[course]} 元/节): ").strip()

            try:
                price = float(new_price)
                if price >= 0:
                    prices[course] = price
                    config_data["price_per_session"] = prices
                    save_all()
                    print(f"已更新 {course} 单价为 {price} 元/节")
                else:
                    print("单价不能为负数")
            except ValueError:
                print("输入无效")

            wait_return()

        elif choice == '2':
            course = input("课程名称: ").strip()
            if course:
                price = input("单价 (元/节): ").strip()
                try:
                    p = float(price)
                    if p >= 0:
                        prices[course] = p
                        config_data["price_per_session"] = prices
                        save_all()
                        print(f"已添加 {course} 单价: {p} 元/节")
                    else:
                        print("单价不能为负数")
                except ValueError:
                    print("输入无效")
            wait_return()

        elif choice == '0':
            return
        else:
            print("无效选择")
            wait_return()


def monthly_fee_statistics():
    """月度课时费统计"""
    clear_screen()
    print("=" * 50)
    print("月度课时费统计")
    print("=" * 50)

    month = input_month()

    records = teaching_data["records"]
    prices = config_data.get("price_per_session", {})

    # 筛选当月记录（排除停课）
    month_records = [
        r for r in records
        if r["date"].startswith(month) and r["type"] != "停课"
    ]

    if not month_records:
        print(f"\n{month} 暂无记录")
        wait_return()
        return

    # 按班级统计
    class_stats = defaultdict(lambda: {"sessions": 0, "amount": 0})
    total_sessions = 0
    total_amount = 0

    for record in month_records:
        course = record["course"]
        price = prices.get(course, 0)
        amount = record["sessions"] * price

        class_stats[record["class"]]["sessions"] += record["sessions"]
        class_stats[record["class"]]["amount"] += amount
        total_sessions += record["sessions"]
        total_amount += amount

    print(f"\n{'='*50}")
    print(f"月份: {month}")
    print(f"{'='*50}")
    print(f"\n{'班级':<15}{'节数':<10}{'单价':<10}{'金额'}")
    print("-" * 50)

    for class_name, stats in sorted(class_stats.items()):
        course = DEFAULT_CLASSES.get(class_name, "未知")
        price = prices.get(course, 0)
        print(f"{class_name:<15}{stats['sessions']:<10}{price:<10.0f}{stats['amount']:.0f} 元")

    print("-" * 50)
    print(f"{'总计':<15}{total_sessions:<10}{'':<10}{total_amount:.0f} 元")

    wait_return()


# ==================== 学期管理 ====================
def manage_semesters():
    """学期管理"""
    while True:
        clear_screen()
        print("=" * 50)
        print("学期管理")
        print("=" * 50)

        semesters = config_data.get("semesters", [])

        if semesters:
            print("\n当前学期:")
            for i, sem in enumerate(semesters, 1):
                cls_names = sem_class_names(sem)
                print(f"  {i}. {sem['name']} ({sem['start']} 至 {sem['end']})  [{len(cls_names)}个班级]")
        else:
            print("\n暂无学期")

        print("\n  1. 添加学期")
        print("  2. 删除学期")
        print("  3. 查看学期班级明细")
        print("  4. 班级转入 / 转出")
        print("  0. 返回")

        choice = input("\n请选择操作: ").strip()

        if choice == '1':
            name = input("学期名称 (如: 2026春季班): ").strip()
            if name:
                start = input_date("开始日期 (YYYY-MM-DD): ")
                end = input_date("结束日期 (YYYY-MM-DD): ")

                # 选择该学期包含的班级
                print("\n选择该学期包含的班级 (用逗号分隔编号，直接回车=全部):")
                all_class_names = list(students_data.keys())
                for i, c in enumerate(all_class_names, 1):
                    print(f"  {i}. {c}")
                cls_choice = input("班级编号: ").strip()
                if cls_choice:
                    try:
                        idxs = [int(x.strip()) - 1 for x in cls_choice.split(',')]
                        sem_class_name_list = [all_class_names[i] for i in idxs if 0 <= i < len(all_class_names)]
                    except ValueError:
                        sem_class_name_list = all_class_names
                else:
                    sem_class_name_list = all_class_names
                # 转为对象格式
                sem_classes = []
                for cn in sem_class_name_list:
                    sem_classes.append({"name": cn, "course": DEFAULT_CLASSES.get(cn, "未知课程")})

                semesters.append({
                    "name": name,
                    "start": start,
                    "end": end,
                    "classes": sem_classes,
                    "students": {}
                })
                config_data["semesters"] = semesters
                save_all()
                print(f"已添加学期: {name}，包含班级: {', '.join(sem_class_name_list)}")

            wait_return()

        elif choice == '2':
            if not semesters:
                print("暂无学期可删除")
                wait_return()
                continue

            names = [s["name"] for s in semesters]
            idx = select_from_list(names, "请选择要删除的学期")
            removed = semesters.pop(idx)
            config_data["semesters"] = semesters
            save_all()
            print(f"已删除学期: {removed['name']}")
            wait_return()

        elif choice == '3':
            view_semester_classes_detail(semesters)
        elif choice == '4':
            transfer_class_between_semesters(semesters)

        elif choice == '0':
            return
        else:
            print("无效选择")
            wait_return()


def view_semester_classes_detail(semesters):
    """查看学期班级明细"""
    if not semesters:
        print("\n暂无学期")
        wait_return()
        return
    clear_screen()
    print("=" * 60)
    print("查看学期班级明细")
    print("=" * 60)
    names = [s["name"] for s in semesters]
    idx = select_from_list(names, "请选择学期")
    sem = semesters[idx]
    cls_names = sem_class_names(sem)
    print(f"\n学期: {sem['name']}  ({sem['start']} 至 {sem['end']})")
    print(f"班级数: {len(cls_names)}")
    total_stu = sum(len(sem.get("students", {}).get(cn, [])) for cn in cls_names)
    print(f"学生总数: {total_stu}")
    if cls_names:
        print(f"\n{'序号':<6}{'班级名称':<20}{'对应课程':<15}{'学生人数'}")
        print("-" * 55)
        for i, cn in enumerate(cls_names, 1):
            cls = sem_find_class(sem, cn)
            course = cls.get("course", "") if cls else ""
            stu_n = len(sem.get("students", {}).get(cn, []))
            print(f"{i:<6}{cn:<20}{course:<15}{stu_n}")
    wait_return()


def transfer_class_between_semesters(semesters):
    """班级转入转出"""
    clear_screen()
    print("=" * 60)
    print("班级转入 / 转出  （将某个班级从来源学期转到目标学期）")
    print("=" * 60)
    if len(semesters) < 2:
        print("\n至少需要2个学期才能进行转入操作")
        wait_return()
        return

    # 选择来源学期
    print("\n① 请选择【来源学期】（转出班级所在学期）:")
    src_names = [f"{s['name']}  [{len(sem_class_names(s))}个班级]" for s in semesters]
    src_idx = select_from_list(src_names, "来源学期")
    src_sem = semesters[src_idx]
    src_class_names = sem_class_names(src_sem)
    if not src_class_names:
        print(f"\n来源学期「{src_sem['name']}」没有任何班级可转出")
        wait_return()
        return

    # 选择班级
    print(f"\n② 来源学期: {src_sem['name']}")
    print("请选择要转出的班级:")
    for i, cn in enumerate(src_class_names, 1):
        cls = sem_find_class(src_sem, cn)
        course = cls.get("course", "") if cls else ""
        stu_n = len(src_sem.get("students", {}).get(cn, []))
        print(f"  {i}. {cn}  (课程:{course}, 学生:{stu_n}人)")
    cls_choice = input("请选择序号: ").strip()
    try:
        ci = int(cls_choice) - 1
        if not (0 <= ci < len(src_class_names)):
            raise ValueError
    except ValueError:
        print("选择无效")
        wait_return()
        return
    class_name = src_class_names[ci]
    src_cls = sem_find_class(src_sem, class_name)
    src_students = list(src_sem.get("students", {}).get(class_name, []))

    # 选择目标学期
    print(f"\n③ 请选择【目标学期】（将 {class_name} 转入该学期）:")
    tgt_options = []
    tgt_indices = []
    for i, s in enumerate(semesters):
        if i != src_idx:
            tgt_options.append(f"{s['name']}  [{len(sem_class_names(s))}个班级]")
            tgt_indices.append(i)
    tgt_opt_idx = select_from_list(tgt_options, "目标学期")
    tgt_idx = tgt_indices[tgt_opt_idx]
    tgt_sem = semesters[tgt_idx]

    # 检查是否同名班级存在
    tgt_exist = sem_find_class(tgt_sem, class_name) is not None
    conflict_mode = "new"
    if tgt_exist:
        print(f"\n⚠ 目标学期「{tgt_sem['name']}」已存在同名班级: {class_name}")
        print("请选择处理方式:")
        print("  1. 合并（学生并集，保留目标班级的课程设置）")
        print("  2. 覆盖（用来源班级替换目标班级，含课程设置和学生名单）")
        print("  3. 取消操作")
        ch = input("请选择 (1-3): ").strip()
        if ch == '1':
            conflict_mode = "merge"
        elif ch == '2':
            conflict_mode = "overwrite"
        else:
            print("已取消")
            wait_return()
            return

    # 确认
    print(f"\n===== 操作确认 =====")
    print(f"来源学期: {src_sem['name']}")
    print(f"目标学期: {tgt_sem['name']}")
    print(f"转出班级: {class_name}")
    print(f"  - 课程: {src_cls.get('course','')}")
    print(f"  - 学生数: {len(src_students)}人")
    if conflict_mode == "merge":
        mode_str = "合并模式（学生并集）"
    elif conflict_mode == "overwrite":
        mode_str = "覆盖模式（替换目标班级）"
    else:
        mode_str = "新建班级"
    print(f"处理方式: {mode_str}")
    confirm = input("\n确认执行？(y/N): ").strip().lower()
    if confirm != 'y':
        print("已取消")
        wait_return()
        return

    # 执行
    src_classes = [c for c in src_sem.get("classes", []) if isinstance(c, dict)]
    src_students_map = src_sem.get("students", {}) if isinstance(src_sem.get("students"), dict) else {}
    tgt_classes = [c for c in tgt_sem.get("classes", []) if isinstance(c, dict)]
    tgt_students_map = tgt_sem.get("students", {}) if isinstance(tgt_sem.get("students"), dict) else {}

    src_idx_cls = next((i for i, c in enumerate(src_classes) if c.get("name") == class_name), -1)
    if src_idx_cls < 0:
        print("找不到来源班级，已取消")
        wait_return()
        return

    if conflict_mode == "new":
        tgt_classes.append({"name": class_name, "course": src_cls.get("course", "")})
        tgt_students_map[class_name] = list(src_students)
        print(f"✓ 在目标学期新建班级")
    elif conflict_mode == "merge":
        # 保留目标班级课程
        old_tgt_stu = list(tgt_students_map.get(class_name, []))
        merged = list(dict.fromkeys(old_tgt_stu + src_students))  # 去重保序
        tgt_students_map[class_name] = merged
        print(f"✓ 合并成功：学生 {len(old_tgt_stu)} + {len(src_students)} → {len(merged)} 人（去重）")
    elif conflict_mode == "overwrite":
        ti = next((i for i, c in enumerate(tgt_classes) if c.get("name") == class_name), -1)
        if ti >= 0:
            tgt_classes[ti] = {"name": class_name, "course": src_cls.get("course", "")}
        tgt_students_map[class_name] = list(src_students)
        print(f"✓ 已覆盖目标班级（课程: {src_cls.get('course','')}，学生: {len(src_students)}人）")

    # 从来源学期删除
    removed = src_classes.pop(src_idx_cls)
    if class_name in src_students_map:
        del src_students_map[class_name]
    src_sem["classes"] = src_classes
    src_sem["students"] = src_students_map
    tgt_sem["classes"] = tgt_classes
    tgt_sem["students"] = tgt_students_map

    config_data["semesters"] = semesters
    save_all()
    print(f"\n✓ 转入完成：{class_name} 从「{src_sem['name']}」→「{tgt_sem['name']}」")
    wait_return()


def semester_statistics():
    """按学期统计"""
    clear_screen()
    print("=" * 50)
    print("学期统计")
    print("=" * 50)

    semesters = config_data.get("semesters", [])
    if not semesters:
        print("\n暂无学期，请先创建学期")
        wait_return()
        return

    idx = select_from_list([s["name"] for s in semesters], "请选择学期")
    semester = semesters[idx]

    start = semester["start"]
    end = semester["end"]

    records = teaching_data["records"]

    # 筛选学期内的记录
    semester_records = [
        r for r in records
        if start <= r["date"] <= end
    ]

    if not semester_records:
        print(f"\n{semester['name']} 暂无记录")
        wait_return()
        return

    # 统计
    total_sessions = sum(r["sessions"] for r in semester_records)
    total_days = len(set(r["date"] for r in semester_records))

    # 按班级统计
    class_stats = defaultdict(lambda: {"sessions": 0, "days": set()})
    for record in semester_records:
        class_stats[record["class"]]["sessions"] += record["sessions"]
        class_stats[record["class"]]["days"].add(record["date"])

    print(f"\n{'='*50}")
    print(f"学期: {semester['name']}")
    print(f"时间: {start} 至 {end}")
    print(f"{'='*50}")
    print(f"\n总体统计:")
    print(f"  总上课节数: {total_sessions}")
    print(f"  总上课天数: {total_days}")

    print(f"\n按班级统计:")
    print(f"  {'班级':<15}{'节数':<10}{'天数'}")
    print(f"  {'-'*35}")
    for class_name, stats in sorted(class_stats.items()):
        print(f"  {class_name:<15}{stats['sessions']:<10}{len(stats['days'])}")

    wait_return()


# ==================== 数据导出 ====================
def export_to_csv():
    """导出记录为CSV"""
    clear_screen()
    print("=" * 50)
    print("导出为CSV")
    print("=" * 50)

    records = teaching_data["records"]
    if not records:
        print("\n暂无记录可导出")
        wait_return()
        return

    # 选择导出范围
    print("\n导出范围:")
    print("  1. 全部记录")
    print("  2. 指定月份")
    print("  3. 指定班级")

    scope = input("请选择 (1-3): ").strip()

    if scope == '2':
        month = input_month()
        filtered = [r for r in records if r["date"].startswith(month)]
        filename = f"records_{month}.csv"
    elif scope == '3':
        classes = list(students_data.keys())
        idx = select_from_list(classes, "请选择班级")
        class_name = classes[idx]
        filtered = [r for r in records if r["class"] == class_name]
        filename = f"records_{class_name}.csv"
    else:
        filtered = records
        filename = f"records_all_{datetime.now().strftime('%Y%m%d_%H%M%S')}.csv"

    if not filtered:
        print("\n筛选后无记录")
        wait_return()
        return

    # 写入CSV
    try:
        with open(filename, 'w', newline='', encoding='utf-8-sig') as f:
            writer = csv.writer(f)
            writer.writerow(["日期", "班级", "课程", "节数", "课程类型", "缺勤学生",
                             "缺勤明细(姓名;待补/已补;缺勤备注)", "备注"])

            for record in sorted(filtered, key=lambda x: x["date"]):
                absent_names = get_absent_names(record.get("absent", []))
                absent_str = '; '.join(absent_names)
                absent_list = normalize_absent(record.get("absent", []))
                detail_parts = []
                for a in absent_list:
                    status = "已补" if a["madeUp"] else "待补"
                    detail = f"{a['name']};{status}"
                    if a.get("absentRemark"):
                        detail += f";{a['absentRemark']}"
                    detail_parts.append(detail)
                absent_detail = "|".join(detail_parts)
                writer.writerow([
                    record["date"],
                    record["class"],
                    record["course"],
                    record["sessions"],
                    record["type"],
                    absent_str,
                    absent_detail,
                    record.get("remark", "")
                ])

        print(f"\n✓ 已导出 {len(filtered)} 条记录到: {filename}")
    except Exception as e:
        print(f"\n导出失败: {e}")

    wait_return()


def generate_class_report():
    """生成班级出勤报表"""
    clear_screen()
    print("=" * 50)
    print("生成班级出勤报表")
    print("=" * 50)

    # 选择班级
    classes = list(students_data.keys())
    class_idx = select_from_list(classes, "请选择班级")
    class_name = classes[class_idx]

    # 输入时间范围
    month = input_month("请输入月份 (YYYY-MM)，直接回车统计全部: ")

    records = teaching_data["records"]

    # 筛选记录
    if month:
        class_records = [
            r for r in records
            if r["class"] == class_name and r["date"].startswith(month)
        ]
        time_range = month
    else:
        class_records = [r for r in records if r["class"] == class_name]
        time_range = "全部"

    if not class_records:
        print(f"\n该时间段暂无记录")
        wait_return()
        return

    # 获取学生名单
    students = students_data.get(class_name, [])
    if not students:
        print("该班级暂无学生名单")
        wait_return()
        return

    # 计算统计数据
    total_sessions = sum(r["sessions"] for r in class_records)

    report_lines = []
    report_lines.append("=" * 60)
    report_lines.append(f"班级出勤报表")
    report_lines.append(f"班级: {class_name}")
    report_lines.append(f"时间范围: {time_range}")
    report_lines.append(f"总上课节数: {total_sessions}")
    report_lines.append("=" * 60)
    report_lines.append("")
    report_lines.append(f"{'学生姓名':<12}{'应到节数':<10}{'缺勤节数':<10}{'出勤率':<10}{'状态'}")
    report_lines.append("-" * 60)

    for student in students:
        pending_sessions = 0
        made_up_sessions = 0
        for record in class_records:
            absent_list = normalize_absent(record.get("absent", []))
            for a in absent_list:
                if a["name"] == student:
                    if a["madeUp"]:
                        made_up_sessions += record["sessions"]
                    else:
                        pending_sessions += record["sessions"]

        absent_sessions = pending_sessions
        present_sessions = total_sessions - absent_sessions
        if total_sessions > 0:
            rate = (present_sessions / total_sessions) * 100
        else:
            rate = 0

        if rate >= 90:
            status = "优秀"
        elif rate >= 75:
            status = "良好"
        elif rate >= 60:
            status = "及格"
        else:
            status = "需关注"

        report_lines.append(
            f"{student:<12}{total_sessions:<10}{pending_sessions:<10}"
            f"{rate:<10.1f}%{status}  (已补{made_up_sessions}节)"
        )

    report_lines.append("")
    report_lines.append("=" * 60)
    report_lines.append(f"生成时间: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}")

    report_text = '\n'.join(report_lines)

    # 显示报表
    print("\n")
    print(report_text)

    # 保存到文件
    filename = f"report_{class_name}_{datetime.now().strftime('%Y%m%d_%H%M%S')}.txt"
    try:
        with open(filename, 'w', encoding='utf-8') as f:
            f.write(report_text)
        print(f"\n✓ 报表已保存到: {filename}")
    except Exception as e:
        print(f"\n保存失败: {e}")

    wait_return()


# ==================== 主菜单 ====================
def main_menu():
    """主菜单"""
    while True:
        clear_screen()
        print("=" * 50)
        print("课外辅导班教学辅助管理系统")
        print("=" * 50)
        print("\n【记录管理】")
        print("  1. 添加上课记录")
        print("  2. 查看所有记录")
        print("  3. 修改记录")
        print("  4. 删除记录")
        print("  5. 按班级查看记录")
        print("\n【学生管理】")
        print("  6. 学生名单管理")
        print("\n【统计分析】")
        print("  7. 月度课时统计")
        print("  8. 学生缺勤总览(按姓名)")
        print("  9. 缺勤总览(按学生分组/含补课状态)")
        print("  10. 出勤率统计")
        print("  11. 月度课时费统计")
        print("\n【学期管理】")
        print("  12. 学期管理")
        print("  13. 学期统计")
        print("\n【数据导出】")
        print("  14. 导出为CSV")
        print("  15. 导出缺勤明细(逐条学生)")
        print("  16. 生成班级出勤报表")
        print("\n【系统设置】")
        print("  17. 课时费单价管理")
        print("\n  0. 退出程序")
        print("=" * 50)

        choice = input("请选择功能 (0-17): ").strip()

        if choice == '1':
            add_record()
        elif choice == '2':
            view_all_records()
        elif choice == '3':
            modify_record()
        elif choice == '4':
            delete_record()
        elif choice == '5':
            view_records_by_class()
        elif choice == '6':
            manage_students()
        elif choice == '7':
            monthly_statistics()
        elif choice == '8':
            student_absent_overview()
        elif choice == '9':
            absent_overview()
        elif choice == '10':
            attendance_rate_statistics()
        elif choice == '11':
            monthly_fee_statistics()
        elif choice == '12':
            manage_semesters()
        elif choice == '13':
            semester_statistics()
        elif choice == '14':
            export_to_csv()
        elif choice == '15':
            export_absent_details()
        elif choice == '16':
            generate_class_report()
        elif choice == '17':
            manage_price()
        elif choice == '0':
            print("\n感谢使用，再见！")
            return
        else:
            print("\n无效选择，请重试")
            wait_return()


# ==================== 程序入口 ====================
def main():
    """主函数"""
    print("\n欢迎使用课外辅导班教学辅助管理系统！")
    print("本系统用于管理班级、学生、上课记录、考勤统计等。\n")

    # 初始化数据
    init_data()

    # 进入主菜单
    try:
        main_menu()
    except KeyboardInterrupt:
        print("\n\n程序被中断，数据已自动保存。")
    except Exception as e:
        print(f"\n程序出错: {e}")
        print("请重启程序，数据已自动保存。")


if __name__ == "__main__":
    main()
