package com.bgrc.attendance.service;

import com.bgrc.attendance.model.User;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class ExcelService {
    private List<User> userCache;

    public ExcelService(){
        initMockData();
    }

    private void initMockData(){
        userCache = new ArrayList<>();

        // mockup 사용자 데이터 추가
        userCache.add(new User("홍길동", "1990-01-15"));
        userCache.add(new User("김철수", "1985-03-22"));
        userCache.add(new User("이영희", "1992-07-08"));
        userCache.add(new User("박민수", "1988-11-30"));
        userCache.add(new User("최지혜", "1995-05-17"));
    }

    public User findUser(String name, String birthDate){
        // 이름과 생년월일 기반 search
        // 엑셀 파일에서 사용자 목록을 로드해야함. => mockData로 잠시 대체
        for (User user : userCache) {
            if(user.getName().equals(name) && user.getBirthDate().equals(birthDate))
                return user;
        }
        return null;
    }
}
