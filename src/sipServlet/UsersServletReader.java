package sipServlet;

import java.io.InputStream;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Unmarshaller;

public class UsersServletReader {

	/**
	 * Lee users.xml (en el mismo paquete que esta clase) y lo convierte en
	 * objetos Users/User con JAXB.
	 */
	public static Users readUsers() throws Exception {
		try (InputStream xml = UsersServletReader.class.getResourceAsStream("users.xml")) {
			if (xml == null) {
				throw new JAXBException("No se encuentra users.xml en el classpath");
			}
			JAXBContext jaxbContext = JAXBContext.newInstance(Users.class);
			Unmarshaller jaxbUnmarshaller = jaxbContext.createUnmarshaller();
			return (Users) jaxbUnmarshaller.unmarshal(xml);
		}
	}

	public static void main(String[] args) {
		try {
			System.out.println(readUsers().toString());
		} catch (Exception e) {
			System.err.println(e.getMessage());
			e.printStackTrace();
		}
	}
}