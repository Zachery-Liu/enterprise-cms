package com.zachery.cms.logging;

import com.zachery.cms.logging.aspect.TargetIdResolver;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class TargetIdResolverTest {
    final TargetIdResolver resolver = new TargetIdResolver();
    public record Result(Long id) {}
    public record Input(Long articleId, String password, Long passwordId) {}
    public void operation(String id, Input request, String password) {}
    Long resolve(String expression,Object result,boolean success) throws Exception {
        return resolver.resolve(expression,getClass().getMethod("operation",String.class,Input.class,String.class),
                new Object[]{"42",new Input(7L,"12345",12345L),"12345"},result,success);
    }
    @Test void supportsNamedAndIndexedIdsAndResultProperties() throws Exception {
        assertThat(resolve("#id",null,false)).isEqualTo(42);
        assertThat(resolve("#p0",null,false)).isEqualTo(42);
        assertThat(resolve("#request.articleId",null,false)).isEqualTo(7);
        assertThat(resolve("#result.id",new Result(9L),true)).isEqualTo(9);
    }
    @Test void resultIsUnavailableForFailuresAndNonPositiveIdsAreNull() throws Exception {
        assertThat(resolve("#result.id",new Result(9L),false)).isNull();
        for (Long id : new Long[]{null,0L,-1L}) assertThat(resolve("#result.id",new Result(id),true)).isNull();
    }
    @Test void credentialsPayloadsExecutableAndInvalidExpressionsCannotProduceTargets() throws Exception {
        for (String expression : new String[]{"#password","#p2","#request.password","#request.passwordId","#request","T(java.lang.System).getProperties()","#result.getClass()","#id='1'","#missingId","#result["})
            assertThat(resolve(expression,new Result(9L),true)).as(expression).isNull();
    }
}
