import { Anchor, Stack, Text } from '@mantine/core'
import { Link } from 'react-router'
import { PageTitle } from '../components/states'

export function NotFoundPage() {
  return (
    <Stack>
      <PageTitle>Page not found</PageTitle>
      <Text>This page does not exist, or the link is out of date.</Text>
      <Anchor component={Link} to="/">
        Go to the home page
      </Anchor>
    </Stack>
  )
}
