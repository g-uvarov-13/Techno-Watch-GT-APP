package app.gtdial.lab;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Narrow parser for the pinned root/text PLB schema, rejecting every other node/tag. */
final class Gt01PlbCodec {
    static final class Node {
        String id,aux;
        int type,start,end,childrenOffset;
        final Map<Integer,Integer> properties=new LinkedHashMap<>();
        final List<Node> children=new ArrayList<>();
    }
    private static final class Reader {
        final byte[] bytes;int p=32,nodes;
        Reader(byte[] bytes){this.bytes=bytes;}
        int word()throws IOException{int n=Gt01DialContainerCodec.u16(bytes,p);p+=2;return n;}
        void skip(int n)throws IOException{Gt01DialContainerCodec.range(bytes,p,n);p+=n;}
        String string()throws IOException{int n=word();Gt01DialContainerCodec.range(bytes,p,n);
            try{String s=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes,p,n)).toString();p+=n;return s;}
            catch(java.nio.charset.CharacterCodingException e){throw new IOException("PLB UTF-8",e);}}
        Node node(int depth)throws IOException {
            Gt01DialContainerCodec.require(depth<=1&&++nodes<=12,"PLB depth/nodes");
            Node node=new Node();node.start=p;node.type=word();node.id=string();node.aux=string();
            Gt01DialContainerCodec.require(depth==0?node.type==15:node.type==11,"PLB тип узла");
            int count=word();Gt01DialContainerCodec.require(count<=8,"PLB свойства");
            for(int i=0;i<count;i++){
                int tag=word(),payload=p;Gt01DialContainerCodec.require(node.properties.put(tag,payload)==null,"Повтор свойства PLB");
                switch(tag){case 0:case 1:skip(4);break;case 2:string();break;
                    case 3:word();string();break;case 4:word();break;case 6:skip(8);break;
                    default:throw new IOException("Неподдерживаемое свойство PLB");}
            }
            node.childrenOffset=p;int children=word();Gt01DialContainerCodec.require(children<=11&&(depth==0||children==0),"PLB children");
            for(int i=0;i<children;i++)node.children.add(node(depth+1));node.end=p;return node;
        }
    }
    static Node parse(byte[] bytes)throws IOException {
        Gt01DialContainerCodec.require(bytes.length<=64*1024&&bytes.length>=32,"PLB размер");
        Gt01DialContainerCodec.magic(bytes,0,new byte[]{'P','L','B',0});
        Gt01DialContainerCodec.require(Gt01DialContainerCodec.positive32(bytes,8)==bytes.length-32,"PLB payload length");
        Reader r=new Reader(bytes);Node root=r.node(0);Gt01DialContainerCodec.require(r.p==bytes.length&&"main".equals(root.id),"PLB EOF/root");return root;
    }
    static byte[] build(byte[] donor,DialProject project)throws IOException {
        project.validate(true);Node root=parse(donor);byte[] prefix=Arrays.copyOfRange(donor,32,root.childrenOffset+2);
        Gt01DialContainerCodec.put16(prefix,prefix.length-2,project.elements.size());ByteArrayOutputStream body=new ByteArrayOutputStream();body.write(prefix);
        for(DialProject.Element element:project.elements){Node node=null;
            for(Node candidate:root.children)if(candidate.id.equals(element.id)){node=candidate;break;}
            Gt01DialContainerCodec.require(node!=null,"Нет donor узла");
            byte[] bytes=Arrays.copyOfRange(donor,node.start,node.end);int rect=property(node,6)-node.start,color=property(node,1)-node.start;
            Gt01DialContainerCodec.put16(bytes,rect,element.x);Gt01DialContainerCodec.put16(bytes,rect+2,element.y);
            Gt01DialContainerCodec.put16(bytes,rect+4,element.width);Gt01DialContainerCodec.put16(bytes,rect+6,element.height);
            Gt01DialContainerCodec.put32(bytes,color,element.argb);body.write(bytes);
        }
        byte[] header=Arrays.copyOf(donor,32);Gt01DialContainerCodec.put32(header,8,body.size());
        ByteArrayOutputStream out=new ByteArrayOutputStream();out.write(header);body.writeTo(out);byte[] result=out.toByteArray();parse(result);return result;
    }
    static int property(Node node,int tag)throws IOException {
        Integer offset=node.properties.get(tag);Gt01DialContainerCodec.require(offset!=null,"Отсутствует PLB свойство");return offset;
    }
    static void validate(byte[] actual,byte[] donor,DialProject project)throws IOException {
        Node root=parse(actual),original=parse(donor);Gt01DialContainerCodec.require(root.children.size()==project.elements.size(),"PLB состав");
        byte[] expectedPrefix=Arrays.copyOfRange(donor,32,original.childrenOffset+2);
        Gt01DialContainerCodec.put16(expectedPrefix,expectedPrefix.length-2,project.elements.size());
        Gt01DialContainerCodec.require(Arrays.equals(expectedPrefix,Arrays.copyOfRange(actual,32,root.childrenOffset+2)),"Изменён root PLB");
        byte[] head=Arrays.copyOf(actual,32);Gt01DialContainerCodec.put32(head,8,donor.length-32);
        Gt01DialContainerCodec.require(Arrays.equals(head,Arrays.copyOf(donor,32)),"PLB заголовок");
        for(int i=0;i<root.children.size();i++){
            Node node=root.children.get(i);DialProject.Element e=project.elements.get(i);
            Gt01DialContainerCodec.require(e.id.equals(node.id),"PLB ID/order");Node base=null;
            for(Node n:original.children)if(n.id.equals(e.id))base=n;
            Gt01DialContainerCodec.require(base!=null,"PLB donor ID");
            int rect=property(node,6),color=property(node,1);
            Gt01DialContainerCodec.require(Gt01DialContainerCodec.u16(actual,rect)==e.x&&Gt01DialContainerCodec.u16(actual,rect+2)==e.y
                &&Gt01DialContainerCodec.u16(actual,rect+4)==e.width&&Gt01DialContainerCodec.u16(actual,rect+6)==e.height
                &&Gt01DialContainerCodec.i32(actual,color)==e.argb,"PLB rect/color");
            byte[] masked=Arrays.copyOfRange(actual,node.start,node.end),baseBytes=Arrays.copyOfRange(donor,base.start,base.end);
            System.arraycopy(donor,property(base,6),masked,rect-node.start,8);System.arraycopy(donor,property(base,1),masked,color-node.start,4);
            Gt01DialContainerCodec.require(Arrays.equals(masked,baseBytes),"Изменено неизменяемое свойство узла PLB");
        }
    }
}
