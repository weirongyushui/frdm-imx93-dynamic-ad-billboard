#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
动作识别接口测试脚本
用于测试 IMX93 开发板调用广告系统的动作识别接口

使用方法:
    python test_action_api.py --action LEFT_SWIPE
    python test_action_api.py --action RIGHT_SWIPE
    python test_action_api.py --action CHANGE_AD
    python test_action_api.py --all  # 测试所有动作
"""

import argparse
import requests
import json
import time

# 配置
BASE_URL = "http://localhost:8083"
TOKEN = "abc123"
HEADERS = {
    "Authorization": f"Bearer {TOKEN}",
    "Content-Type": "application/json"
}

def send_action(action):
    """发送动作请求"""
    url = f"{BASE_URL}/api/action"
    payload = {"action": action}
    
    try:
        print(f"正在发送动作: {action}")
        print(f"请求URL: {url}")
        print(f"请求头: {HEADERS}")
        print(f"请求体: {json.dumps(payload)}")
        
        response = requests.post(url, headers=HEADERS, json=payload)
        
        print(f"\n响应状态码: {response.status_code}")
        print(f"响应内容: {response.text}")
        
        try:
            result = response.json()
            if result.get("code") == 200:
                print(f"✅ 动作 {action} 执行成功")
            else:
                print(f"❌ 动作 {action} 执行失败: {result.get('message')}")
        except json.JSONDecodeError:
            print(f"❌ 响应不是有效的JSON格式")
            
        return response.status_code == 200
        
    except requests.exceptions.RequestException as e:
        print(f"❌ 请求失败: {e}")
        return False

def test_all_actions():
    """测试所有动作"""
    actions = ["LEFT_SWIPE", "RIGHT_SWIPE", "CHANGE_AD"]
    
    print("=" * 60)
    print("开始测试所有动作")
    print("=" * 60)
    
    for action in actions:
        print(f"\n{'=' * 40}")
        send_action(action)
        print(f"{'=' * 40}")
        time.sleep(1.5)  # 等待超过限流时间
    
    print("\n" + "=" * 60)
    print("所有动作测试完成")
    print("=" * 60)

def test_throttle():
    """测试限流功能"""
    print("=" * 60)
    print("测试限流功能（1秒内多次调用同一动作）")
    print("=" * 60)
    
    action = "LEFT_SWIPE"
    print(f"\n连续发送3次 {action} 动作（间隔200ms）")
    
    for i in range(3):
        print(f"\n--- 第 {i+1} 次请求 ---")
        send_action(action)
        time.sleep(0.2)  # 200ms间隔
    
    print("\n" + "=" * 60)
    print("限流测试完成（只有第一次会真正执行）")
    print("=" * 60)

def test_invalid_token():
    """测试无效Token"""
    print("=" * 60)
    print("测试无效Token")
    print("=" * 60)
    
    url = f"{BASE_URL}/api/action"
    payload = {"action": "LEFT_SWIPE"}
    headers = {
        "Authorization": "Bearer invalid_token_123",
        "Content-Type": "application/json"
    }
    
    try:
        response = requests.post(url, headers=headers, json=payload)
        print(f"响应状态码: {response.status_code}")
        print(f"响应内容: {response.text}")
        
        if response.status_code == 401:
            print("✅ 无效Token正确返回401")
        else:
            print("❌ 无效Token未正确处理")
            
    except requests.exceptions.RequestException as e:
        print(f"❌ 请求失败: {e}")

def main():
    parser = argparse.ArgumentParser(description="动作识别接口测试脚本")
    parser.add_argument("--action", choices=["LEFT_SWIPE", "RIGHT_SWIPE", "CHANGE_AD"],
                        help="指定要测试的动作")
    parser.add_argument("--all", action="store_true", help="测试所有动作")
    parser.add_argument("--throttle", action="store_true", help="测试限流功能")
    parser.add_argument("--invalid-token", action="store_true", help="测试无效Token")
    parser.add_argument("--url", default=BASE_URL, help="指定服务器URL")
    parser.add_argument("--token", default=TOKEN, help="指定Token")
    
    args = parser.parse_args()
    
    global BASE_URL, TOKEN, HEADERS
    BASE_URL = args.url
    TOKEN = args.token
    HEADERS = {
        "Authorization": f"Bearer {TOKEN}",
        "Content-Type": "application/json"
    }
    
    if args.action:
        send_action(args.action)
    elif args.all:
        test_all_actions()
    elif args.throttle:
        test_throttle()
    elif args.invalid_token:
        test_invalid_token()
    else:
        parser.print_help()

if __name__ == "__main__":
    main()
